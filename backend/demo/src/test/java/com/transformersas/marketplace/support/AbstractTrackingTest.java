package com.transformersas.marketplace.support;

import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.web.controller.WebhookSignatureVerifier;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base de las pruebas de seguimiento logístico (CU-24 y CU-25): proveedores simulados limpios, firma del webhook y
 * siembra de pedidos con envío y de devoluciones con seguimiento. Todo se siembra directo en BD para poder probar cada
 * regla sin pasar por el checkout.
 */
public abstract class AbstractTrackingTest extends AbstractIntegrationTest {

    protected static final String SHIPMENTS_WEBHOOK = "/api/logistics/webhooks/shipments";
    protected static final String RETURNS_WEBHOOK = "/api/logistics/webhooks/returns";

    @Autowired protected SimulatedLogisticsGateway logistics;
    @Autowired protected SimulatedExternalNotificationGateway notices;
    @Autowired protected WebhookSignatureVerifier signer;

    @BeforeEach
    void resetProviders() {
        logistics.reset();
        notices.reset();
    }

    // ---------- Siembra ----------

    /** Producto de la tienda 1 (se siembra uno por llamada). */
    protected long product() {
        return seedProduct(1, "Lámpara " + System.nanoTime(), 10, "100.00");
    }

    /**
     * Pedido del comprador en el estado dado con su envío ya creado (SIM-order-N / TRK-N), como queda tras el
     * despacho de CU-23. Devuelve el id del pedido.
     */
    protected long shippedOrder(long buyerAccountId, String status) {
        long order = seedOrder(1, status, product(), 1, "100.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerAccountId, order);
        jdbc.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                VALUES (?, ?, ?, ?, 'CREATED', NOW(6))""", order, "SIM-order-" + order, "TRK-" + order, "order-" + order);
        return order;
    }

    protected long shipmentId(long order) {
        return jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ?", Long.class, order);
    }

    /** Devolución aprobada con seguimiento registrado directamente (lo que CU-19 hará al llamar al caso de uso). */
    protected long returnShipment(long returnId, long buyerAccountId, long storeId, String status, int failedPickups) {
        jdbc.update("""
                INSERT INTO return_shipments(return_id, buyer_account_id, store_id, provider_return_id, tracking_code,
                                             status, failed_pickups, pickup_stopped, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))""", returnId, buyerAccountId, storeId,
                "SIM-return-" + returnId, "TRK-R" + returnId, status, failedPickups, failedPickups >= 3);
        return jdbc.queryForObject("SELECT id FROM return_shipments WHERE return_id = ?", Long.class, returnId);
    }

    // ---------- Consultas de estado ----------

    protected String orderStatus(long order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    protected String returnStatus(long returnId) {
        return jdbc.queryForObject("SELECT status FROM return_shipments WHERE return_id = ?", String.class, returnId);
    }

    protected List<String> orderEventOutcomes(long order) {
        return jdbc.queryForList("SELECT CONCAT(event_type, ':', outcome) FROM shipment_tracking_events "
                + "WHERE order_id = ? ORDER BY id", String.class, order);
    }

    protected List<String> historyStatuses(long order) {
        return jdbc.queryForList("SELECT to_status FROM order_status_history WHERE order_id = ? ORDER BY id",
                String.class, order);
    }

    protected List<String> notificationKeys() {
        return jdbc.queryForList("SELECT event_key FROM notifications ORDER BY id", String.class);
    }

    // ---------- Webhook ----------

    protected static Instant secondsAgo(long seconds) {
        return Instant.now().minus(seconds, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MILLIS);
    }

    /** Publica en el webhook la actualización firmada como lo haría el servicio logístico. */
    protected ResultActions signedPost(String path, Map<String, ?> body) throws Exception {
        byte[] bytes = json.writeValueAsBytes(body);
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSignatureVerifier.HEADER, signer.signature(bytes)).content(bytes));
    }

    protected static Map<String, Object> shipmentEvent(long order, String eventId, String type, Instant at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("shipmentId", "SIM-order-" + order);
        body.put("trackingCode", "TRK-" + order);
        body.put("type", type);
        body.put("occurredAt", at.toString());
        return body;
    }

    protected static Map<String, Object> returnEvent(long returnId, String eventId, String type, Instant at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("returnId", "SIM-return-" + returnId);
        body.put("trackingCode", "TRK-R" + returnId);
        body.put("type", type);
        body.put("occurredAt", at.toString());
        return body;
    }

    protected ResultActions sendShipmentEvent(long order, String eventId, String type, Instant at) throws Exception {
        return signedPost(SHIPMENTS_WEBHOOK, shipmentEvent(order, eventId, type, at));
    }

    protected ResultActions sendReturnEvent(long returnId, String eventId, String type, Instant at) throws Exception {
        return signedPost(RETURNS_WEBHOOK, returnEvent(returnId, eventId, type, at));
    }
}
