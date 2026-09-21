package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnMethod;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proveedor logístico simulado (por defecto, RNF-037): en proceso, determinista e idempotente por Idempotency-Key.
 * El modo permite probar éxito y fallos en la demo (logistics.simulated.mode) o cambiarlo en caliente desde pruebas.
 *
 * <p>Seguimiento (CU-24/CU-25): las pruebas encolan actualizaciones con publish/publishReturn y la demo puede activar
 * logistics.simulated.script=HAPPY_PATH, que hace avanzar cada envío por Recogido, En camino y Entregado (y cada
 * retorno por Recogido, En retorno y Entregado al vendedor) cada logistics.simulated.step desde la primera consulta.
 * La guía de un envío "SIM-order-N" es "TRK-N" y la de un retorno "SIM-return-N" es "TRK-RN".
 *
 * <p>Retornos (CU-19): ofrece los métodos PICKUP y DROP_OFF y crea el retorno de forma idempotente por "return-N", con los
 * mismos modos OK/UNAVAILABLE/REJECT. Un método que no esté disponible se rechaza como un 4xx (setUnavailableReturnMethods).
 */
@Component
@ConditionalOnProperty(name = "logistics.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedLogisticsGateway implements LogisticsGateway {

    public enum Mode { OK, UNAVAILABLE, REJECT }

    public enum Script { NONE, HAPPY_PATH }

    private volatile Mode mode;
    @Value("${logistics.simulated.script:NONE}")
    private volatile Script script = Script.NONE;
    @Value("${logistics.simulated.step:15s}")
    private volatile Duration step = Duration.ofSeconds(15);

    /** Métodos de retorno que el simulador ofrece. */
    public static final List<ReturnMethod> RETURN_METHODS = List.of(
            new ReturnMethod("PICKUP", "Recogida en mi dirección"),
            new ReturnMethod("DROP_OFF", "Entrega en un punto de despacho"));

    private final Map<String, ShipmentReceipt> byKey = new ConcurrentHashMap<>();
    private final Map<String, ReturnReceipt> returnsByKey = new ConcurrentHashMap<>();
    private final java.util.Set<String> unavailableReturnMethods = ConcurrentHashMap.newKeySet();
    private final Map<String, List<TrackingUpdate>> shipmentUpdates = new ConcurrentHashMap<>();
    private final Map<String, List<ReturnTrackingUpdate>> returnUpdates = new ConcurrentHashMap<>();
    private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();
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

    @Override
    public List<TrackingUpdate> fetchShipmentUpdates(String providerShipmentId) {
        requests.incrementAndGet();
        failIfNotAvailable();
        List<TrackingUpdate> updates = new ArrayList<>(shipmentUpdates.getOrDefault(providerShipmentId, List.of()));
        if (script == Script.HAPPY_PATH) {
            String tracking = "TRK-" + providerShipmentId.replace("SIM-order-", "");
            Instant start = firstSeen.computeIfAbsent(providerShipmentId, id -> Instant.now());
            List<ShipmentEventType> path = List.of(ShipmentEventType.PICKED_UP, ShipmentEventType.IN_TRANSIT,
                    ShipmentEventType.DELIVERED);
            for (int i = 0; i < path.size(); i++) {
                Instant at = start.plus(step.multipliedBy(i + 1L));
                if (!at.isAfter(Instant.now())) {
                    ShipmentEventType type = path.get(i);
                    updates.add(new TrackingUpdate("sim-" + providerShipmentId + "-" + type, providerShipmentId,
                            tracking, type, at, "Simulador logístico", "Bogotá",
                            type == ShipmentEventType.DELIVERED ? new TrackingEvidence("CODE", "SIM-POD-" + tracking) : null));
                }
            }
        }
        return updates;
    }

    @Override
    public List<ReturnTrackingUpdate> fetchReturnUpdates(String providerReturnId) {
        requests.incrementAndGet();
        failIfNotAvailable();
        List<ReturnTrackingUpdate> updates = new ArrayList<>(returnUpdates.getOrDefault(providerReturnId, List.of()));
        if (script == Script.HAPPY_PATH) {
            String tracking = "TRK-R" + providerReturnId.replace("SIM-return-", "");
            Instant start = firstSeen.computeIfAbsent(providerReturnId, id -> Instant.now());
            List<ReturnEventType> path = List.of(ReturnEventType.PICKED_UP, ReturnEventType.IN_TRANSIT,
                    ReturnEventType.DELIVERED_TO_SELLER);
            for (int i = 0; i < path.size(); i++) {
                Instant at = start.plus(step.multipliedBy(i + 1L));
                if (!at.isAfter(Instant.now())) {
                    ReturnEventType type = path.get(i);
                    updates.add(new ReturnTrackingUpdate("sim-" + providerReturnId + "-" + type, providerReturnId,
                            tracking, type, at, "Simulador logístico", "Bogotá",
                            type == ReturnEventType.DELIVERED_TO_SELLER
                                    ? new TrackingEvidence("CODE", "SIM-POD-" + tracking) : null));
                }
            }
        }
        return updates;
    }

    @Override
    public List<ReturnMethod> fetchReturnMethods(Long orderId, Long storeId) {
        requests.incrementAndGet();
        failIfNotAvailable();
        return RETURN_METHODS.stream().filter(method -> !unavailableReturnMethods.contains(method.code())).toList();
    }

    @Override
    public ReturnReceipt createReturn(ReturnRequestData request, String idempotencyKey) {
        requests.incrementAndGet();
        return switch (mode) {
            case UNAVAILABLE -> throw new LogisticsUnavailableException("Servicio logístico simulado no disponible", null);
            case REJECT -> throw new LogisticsRejectedException("Solicitud rechazada por el servicio logístico simulado", null);
            case OK -> {
                boolean offered = RETURN_METHODS.stream().anyMatch(method -> method.code().equals(request.methodCode()))
                        && !unavailableReturnMethods.contains(request.methodCode());
                if (!offered) {
                    throw new LogisticsRejectedException("Método de retorno no disponible: " + request.methodCode(), null);
                }
                // Misma clave, mismo retorno: la creación es idempotente como exige el contrato.
                yield returnsByKey.computeIfAbsent(idempotencyKey, key ->
                        new ReturnReceipt("SIM-" + key, "TRK-R" + key.replace("return-", "")));
            }
        };
    }

    private void failIfNotAvailable() {
        switch (mode) {
            case UNAVAILABLE -> throw new LogisticsUnavailableException("Servicio logístico simulado no disponible", null);
            case REJECT -> throw new LogisticsRejectedException("Consulta rechazada por el servicio logístico simulado", null);
            case OK -> { }
        }
    }

    /** Encola una actualización de envío que el proveedor informará en las próximas consultas (uso de pruebas). */
    public void publish(TrackingUpdate update) {
        shipmentUpdates.computeIfAbsent(update.shipmentId(), id -> new CopyOnWriteArrayList<>()).add(update);
    }

    /** Encola una actualización de retorno que el proveedor informará en las próximas consultas (uso de pruebas). */
    public void publishReturn(ReturnTrackingUpdate update) {
        returnUpdates.computeIfAbsent(update.returnId(), id -> new CopyOnWriteArrayList<>()).add(update);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setScript(Script script) {
        this.script = script;
    }

    /** Deja de ofrecer estos métodos de retorno (uso de pruebas): simula uno que ya no está disponible. */
    public void setUnavailableReturnMethods(java.util.Collection<String> codes) {
        unavailableReturnMethods.clear();
        unavailableReturnMethods.addAll(codes);
    }

    /** Solicitudes recibidas (incluye las fallidas): permite comprobar que no hubo llamadas duplicadas. */
    public int requestCount() {
        return requests.get();
    }

    /** Retornos distintos creados en el proveedor simulado. */
    public int distinctReturns() {
        return returnsByKey.size();
    }

    /** Envíos distintos creados en el proveedor simulado. */
    public int distinctShipments() {
        return byKey.size();
    }

    public void reset() {
        byKey.clear();
        returnsByKey.clear();
        unavailableReturnMethods.clear();
        shipmentUpdates.clear();
        returnUpdates.clear();
        firstSeen.clear();
        requests.set(0);
        mode = Mode.OK;
        script = Script.NONE;
    }
}
