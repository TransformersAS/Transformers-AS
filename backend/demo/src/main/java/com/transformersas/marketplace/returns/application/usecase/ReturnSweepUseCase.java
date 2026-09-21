package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.returns.infrastructure.config.ReturnProperties;
import com.transformersas.marketplace.shared.audit.ActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El barrido de devoluciones: cierra las inspecciones cuyas 24 h vencieron sin problema y reintenta los reembolsos que no
 * quedaron completados (D-extras, RNF-043, RNF-047). No hay ninguna tarea por devolución: una consulta indexada por
 * (estado, próxima acción) trae solo lo vencido, en lotes pequeños.
 *
 * <p>Dos réplicas pueden correrlo a la vez sin pisarse: cada vuelta reserva su lote con {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}, que salta lo que ya tiene otra, y le pone una <i>reserva</i> (aplaza su próxima acción unos minutos) antes de
 * soltar el bloqueo. Así nadie más la toma mientras se procesa, y si el proceso se cae la reserva vence sola y otra vuelta la
 * retoma. El reembolso se pide fuera de toda transacción (llama a un servicio externo) y es idempotente por la clave
 * return-{id}: repetirlo nunca cobra dos veces, ni aunque dos réplicas coincidan.
 */
@Service
public class ReturnSweepUseCase {
    private static final Logger log = LoggerFactory.getLogger(ReturnSweepUseCase.class);

    /** Lo que hizo una vuelta, para las pruebas y el log. */
    public record Summary(int claimed, int refundsCompleted, int refundsNotCompleted) {
    }

    private final ReturnRequestRepository repository;
    private final RequestRefundUseCase refunds;
    private final ReturnRecorder recorder;
    private final ReturnPolicy policy;
    private final ReturnProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReturnSweepUseCase(ReturnRequestRepository repository, RequestRefundUseCase refunds,
                              ReturnRecorder recorder, ReturnPolicy policy, ReturnProperties properties, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.refunds = refunds;
        this.recorder = recorder;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Una vuelta: reserva un lote, cierra las inspecciones vencidas y procesa los reembolsos. */
    public Summary runOnce() {
        List<Long> claimed = transaction.execute(status -> claimBatch());
        int completed = 0;
        int notCompleted = 0;
        for (Long id : claimed) {
            try {
                if (settleRefund(id)) {
                    completed++;
                } else {
                    notCompleted++;
                }
            } catch (RuntimeException failure) {
                // Una devolución con problemas no debe frenar a las demás; su reserva vence y se reintenta.
                log.error("No se pudo procesar la devolución returnId={}", id, failure);
            }
        }
        return new Summary(claimed.size(), completed, notCompleted);
    }

    /**
     * Reserva las devoluciones vencidas. Las inspecciones sin problema pasan a Reembolso pendiente; todas quedan reservadas
     * por el tiempo de un reintento.
     */
    private List<Long> claimBatch() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = repository.lockDueIds(now, properties.sweep().batchSize());
        for (Long id : ids) {
            ReturnRequest request = repository.lockById(id).orElseThrow();
            if (request.getStatus() == ReturnStatus.IN_INSPECTION) {
                ReturnEvent event = request.beginRefund(now);
                recorder.record(request, event, now);
            }
            request.lease(now.plus(policy.refundRetryDelay()), now);
            repository.save(request);
        }
        return ids;
    }

    /**
     * Pide el reembolso y deja constancia del resultado. Devuelve verdadero si quedó completado. Un fallo, un rechazo del
     * tope por pedido o una respuesta pendiente cuentan como intento no completado y agendan el siguiente con la misma clave,
     * hasta el máximo configurado; después queda para revisión manual (sigue Reembolso pendiente, sin próxima acción).
     */
    private boolean settleRefund(Long id) {
        ReturnRequest request = repository.findById(id).orElseThrow();
        if (request.getStatus() != ReturnStatus.REFUND_PENDING) {
            return false;
        }
        Refund refund = null;
        String failure = null;
        try {
            refund = refunds.execute(new RefundCommand(request.getOrderId(), request.getRefundAmount(),
                    request.refundKey(), ActorType.SYSTEM, null));
        } catch (RuntimeException rejected) {
            failure = rejected.getMessage();
        }
        boolean completed = refund != null && refund.status() == RefundStatus.COMPLETED;
        String reason = failure != null ? failure : refund == null ? null
                : refund.status() == RefundStatus.FAILED ? refund.lastError() : "Reembolso pendiente de confirmar";
        transaction.executeWithoutResult(status -> {
            ReturnRequest locked = repository.lockById(id).orElseThrow();
            if (locked.getStatus() != ReturnStatus.REFUND_PENDING) {
                return; // otra réplica ya lo resolvió
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (completed) {
                ReturnEvent event = locked.refundCompleted(now);
                repository.save(locked);
                recorder.record(locked, event, now);
                recorder.notifyBuyer(locked, "REFUND_COMPLETED", "REFUND_COMPLETED", "Tu reembolso fue completado",
                        "Reembolsamos " + locked.getRefundAmount().toPlainString() + " por la devolución de "
                                + locked.getLine().productName() + ".");
            } else {
                ReturnEvent event = locked.refundNotCompleted(now, reason, policy);
                repository.save(locked);
                recorder.record(locked, event, now);
            }
        });
        return completed;
    }
}
