package com.transformersas.marketplace.payments.infrastructure.gateway;

import com.transformersas.marketplace.payments.domain.model.RefundGatewayResult;
import com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.payments.domain.repository.RefundGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pasarela de reembolsos simulada (RNF-037), determinista e idempotente por clave. Igual que MockPaymentGateway,
 * PaymentGateway no tiene adaptador HTTP todavía: este es el único. payments.refund.simulated.mode (o setMode en
 * pruebas) permite simular éxito, aceptación pendiente, indisponibilidad y rechazo.
 */
@Component
public class SimulatedRefundGateway implements RefundGateway {

    public enum Mode { OK, PENDING, UNAVAILABLE, REJECT }

    private volatile Mode mode;
    private final Map<String, String> completedByKey = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    public SimulatedRefundGateway(@Value("${payments.refund.simulated.mode:OK}") Mode mode) {
        this.mode = mode;
    }

    @Override
    public RefundGatewayResult refund(String idempotencyKey, Long orderId, BigDecimal amount) {
        requests.incrementAndGet();
        return switch (mode) {
            case UNAVAILABLE -> throw new RefundRequestFailedException("Pasarela de reembolsos simulada no disponible", null);
            case REJECT -> throw new RefundRequestFailedException("Reembolso rechazado por la pasarela simulada", null);
            case PENDING -> new RefundGatewayResult(RefundStatus.PENDING, null);
            // Misma clave, mismo reembolso: nunca se reembolsa dos veces.
            case OK -> new RefundGatewayResult(RefundStatus.COMPLETED,
                    completedByKey.computeIfAbsent(idempotencyKey, key -> "SIM-REFUND-" + key));
        };
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Solicitudes recibidas, exitosas o no. */
    public int requestCount() {
        return requests.get();
    }

    /** Reembolsos distintos efectivamente realizados. */
    public int distinctRefunds() {
        return completedByKey.size();
    }

    public void reset() {
        completedByKey.clear();
        requests.set(0);
        mode = Mode.OK;
    }
}
