package com.transformersas.marketplace.payments.application.usecase;

import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundGatewayResult;
import com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.payments.domain.repository.RefundGateway;
import com.transformersas.marketplace.payments.domain.repository.RefundRepository;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Reembolsos idempotentes por idempotencyKey (D10, RNF-009, RNF-043), reutilizables por CU-11, CU-19 y CU-23.
 *
 * <p>Dos pasos: (1) {@link #registerPending} guarda la solicitud PENDING dentro de la transacción del cambio de
 * negocio que la origina (p. ej. la cancelación), y (2) {@link #execute} llama a la pasarela FUERA de toda
 * transacción y registra el resultado. Repetir la misma clave nunca crea otro reembolso ni cobra dos veces: si ya
 * está COMPLETED no llama a la pasarela; si está PENDING o FAILED reintenta con la misma clave (idempotente en la
 * pasarela). Sin reintentos automáticos. El resultado, sea cual sea, NUNCA revierte lo que originó el reembolso.
 */
@Service
public class RequestRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(RequestRefundUseCase.class);

    private final RefundRepository refunds;
    private final RefundGateway gateway;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public RequestRefundUseCase(RefundRepository refunds, RefundGateway gateway, AuditRecorder audit,
                                TransactionTemplate transaction) {
        this.refunds = refunds;
        this.gateway = gateway;
        this.audit = audit;
        this.transaction = transaction;
    }

    /** Paso 1: registra la solicitud (idempotente) dentro de la transacción del llamador. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund registerPending(RefundCommand command) {
        return register(command);
    }

    /** Paso 2 (y flujo completo si aún no se registró): procesa el reembolso en la pasarela y guarda el resultado. */
    public Refund execute(RefundCommand command) {
        Refund refund = transaction.execute(status -> register(command));
        if (refund.status() == RefundStatus.COMPLETED) {
            return refund; // idempotente: ya se reembolsó, no se llama de nuevo a la pasarela
        }
        return process(refund, command);
    }

    private Refund register(RefundCommand command) {
        var insertion = refunds.insertIfAbsent(new Refund(null, command.orderId(), command.amount(),
                command.idempotencyKey(), RefundStatus.PENDING, null, 0, null, CorrelationContext.current(),
                LocalDateTime.now()));
        if (insertion.created()) {
            audit.record(command.actorType(), command.actorId(), "REFUND_REQUESTED", "ORDER", command.orderId(),
                    AuditOutcome.PENDING, Map.of("refundId", insertion.refund().id(),
                            "idempotencyKey", command.idempotencyKey(), "amount", command.amount().toPlainString()));
        }
        return insertion.refund();
    }

    private Refund process(Refund refund, RefundCommand command) {
        RefundGatewayResult result;
        try {
            result = gateway.refund(refund.idempotencyKey(), refund.orderId(), refund.amount());
        } catch (RefundRequestFailedException failure) {
            log.warn("Reembolso fallido refundId={} orderId={} motivo={}", refund.id(), refund.orderId(),
                    failure.getMessage());
            refunds.markFailed(refund.id(), failure.getMessage());
            audit.record(command.actorType(), command.actorId(), "REFUND_RESULT", "ORDER", refund.orderId(),
                    AuditOutcome.FAILURE, Map.of("refundId", refund.id(), "idempotencyKey", refund.idempotencyKey(),
                            "reason", String.valueOf(failure.getMessage())));
            return refunds.findById(refund.id()).orElseThrow();
        }

        if (result.status() == RefundStatus.COMPLETED) {
            // Solo el primero en completar audita: dos ejecuciones simultáneas dejan un único registro.
            if (refunds.markCompleted(refund.id(), result.providerReference())) {
                audit.record(command.actorType(), command.actorId(), "REFUND_RESULT", "ORDER", refund.orderId(),
                        AuditOutcome.SUCCESS, Map.of("refundId", refund.id(), "idempotencyKey", refund.idempotencyKey(),
                                "providerReference", String.valueOf(result.providerReference())));
            }
        } else {
            refunds.markPending(refund.id());
            log.info("Reembolso aceptado sin confirmar refundId={} orderId={}", refund.id(), refund.orderId());
        }
        return refunds.findById(refund.id()).orElseThrow();
    }
}
