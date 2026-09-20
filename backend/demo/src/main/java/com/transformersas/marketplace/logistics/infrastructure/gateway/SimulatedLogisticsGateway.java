package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proveedor logístico simulado (por defecto, RNF-037): en proceso, determinista e idempotente por Idempotency-Key.
 * El modo permite probar éxito y fallos en la demo (logistics.simulated.mode) o cambiarlo en caliente desde pruebas.
 */
@Component
@ConditionalOnProperty(name = "logistics.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedLogisticsGateway implements LogisticsGateway {

    public enum Mode { OK, UNAVAILABLE, REJECT }

    private volatile Mode mode;
    private final Map<String, ShipmentReceipt> byKey = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    public SimulatedLogisticsGateway(@Value("${logistics.simulated.mode:OK}") Mode mode) {
        this.mode = mode;
    }

    @Override
    public ShipmentReceipt createShipment(ShipmentRequest request, String idempotencyKey) {
        requests.incrementAndGet();
        return switch (mode) {
            case UNAVAILABLE -> throw new LogisticsUnavailableException("Servicio logístico simulado no disponible", null);
            case REJECT -> throw new LogisticsRejectedException("Solicitud rechazada por el servicio logístico simulado", null);
            // Misma clave, mismo envío: la creación es idempotente como exige el contrato.
            case OK -> byKey.computeIfAbsent(idempotencyKey, key ->
                    new ShipmentReceipt("SIM-" + key, "TRK-" + key.replace("order-", "")));
        };
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Solicitudes recibidas (incluye las fallidas): permite comprobar que no hubo llamadas duplicadas. */
    public int requestCount() {
        return requests.get();
    }

    /** Envíos distintos creados en el proveedor simulado. */
    public int distinctShipments() {
        return byKey.size();
    }

    public void reset() {
        byKey.clear();
        requests.set(0);
        mode = Mode.OK;
    }
}
