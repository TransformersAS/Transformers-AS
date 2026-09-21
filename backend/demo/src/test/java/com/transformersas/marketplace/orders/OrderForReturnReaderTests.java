package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lo que devoluciones lee de un pedido (CU-19): sus líneas con id, su estado y la fecha real de entrega. */
class OrderForReturnReaderTests extends AbstractIntegrationTest {
    private static final LocalDateTime DELIVERED = LocalDateTime.of(2026, 9, 1, 15, 30);

    @Autowired OrderForReturnReader reader;

    private long buyerId;
    private long otherBuyerId;
    private long product;

    @BeforeEach
    void seed() {
        buyerId = createAccount("comprador@example.com", "COMPRADOR");
        otherBuyerId = createAccount("otra@example.com", "COMPRADOR");
        product = seedProduct(1, "Lámpara", 5, "10.00");
    }

    private long order(String status, long buyer) {
        long id = seedOrder(1, status, product, 2, "10.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyer, id);
        return id;
    }

    private void historyDeliveredAt(long orderId, LocalDateTime at) {
        jdbc.update("UPDATE order_status_history SET created_at = ? WHERE order_id = ? AND to_status = 'DELIVERED'", at,
                orderId);
    }

    private long shipment(long orderId) {
        jdbc.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                VALUES (?, ?, ?, ?, 'DELIVERED', NOW(6))""", orderId, "prov-" + orderId, "TRK-" + orderId,
                "order-" + orderId);
        return jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ?", Long.class, orderId);
    }

    private void trackingEvent(long orderId, String eventId, String type, String outcome, LocalDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO shipment_tracking_events(shipment_id, order_id, provider_event_id, event_type, occurred_at,
                    received_at, source, outcome, correlation_id)
                VALUES (?, ?, ?, ?, ?, NOW(6), 'WEBHOOK', ?, 'test')""", shipmentOf(orderId), orderId, eventId, type,
                occurredAt, outcome);
    }

    private long shipmentOf(long orderId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM shipments WHERE order_id = ?", Long.class, orderId);
        return ids.isEmpty() ? shipment(orderId) : ids.get(0);
    }

    @Test
    void aDeliveredOrderOfTheBuyerComesWithItsLinesAndIdsAndTheRegistrationDateAsFallback() {
        long orderId = order("DELIVERED", buyerId);
        historyDeliveredAt(orderId, DELIVERED);
        long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, orderId);

        OrderForReturn found = reader.findForBuyer(orderId, buyerId).orElseThrow();

        assertThat(found.delivered()).isTrue();
        assertThat(found.deliveredAt()).isEqualTo(DELIVERED);
        assertThat(found.storeId()).isEqualTo(1L);
        // La recogida sale del snapshot de entrega del pedido y no se expone en toString.
        assertThat(found.pickup()).isNotNull();
        assertThat(found.pickup().street()).isEqualTo("Calle 1 # 2-3");
        assertThat(found.pickup().city()).isEqualTo("Bogotá");
        assertThat(found.pickup().postalCode()).isEqualTo("110111");
        assertThat(found.pickup().toString()).doesNotContain("Calle");
        assertThat(found.lines()).singleElement().satisfies(line -> {
            assertThat(line.orderItemId()).isEqualTo(itemId);
            assertThat(line.productId()).isEqualTo(product);
            assertThat(line.productName()).isEqualTo("Lámpara");
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.unitPrice()).isEqualByComparingTo(new BigDecimal("10.00"));
        });
    }

    @Test
    void theProvidersRealDeliveryTimeWinsOverTheRegistrationTimeAndOnlyAppliedEventsCount() {
        long orderId = order("DELIVERED", buyerId);
        historyDeliveredAt(orderId, DELIVERED.plusDays(2));
        trackingEvent(orderId, "e1", "DELIVERED", "RECORDED", DELIVERED.minusDays(9));
        trackingEvent(orderId, "e2", "IN_TRANSIT", "APPLIED", DELIVERED.minusDays(9));
        trackingEvent(orderId, "e3", "DELIVERED", "APPLIED", DELIVERED);
        trackingEvent(orderId, "e4", "DELIVERED", "APPLIED", DELIVERED.plusHours(5));

        assertThat(reader.findForBuyer(orderId, buyerId).orElseThrow().deliveredAt()).isEqualTo(DELIVERED);
    }

    @Test
    void anOrderThatIsNotDeliveredHasNoDeliveryDateEvenIfItHasHistoryOfAnotherState() {
        long orderId = order("IN_TRANSIT", buyerId);

        OrderForReturn found = reader.findForBuyer(orderId, buyerId).orElseThrow();

        assertThat(found.delivered()).isFalse();
        assertThat(found.deliveredAt()).isNull();
    }

    @Test
    void aDeliveredOrderWithoutAnyDeliveryRecordHasANullDateInsteadOfAnInventedOne() {
        long orderId = order("DELIVERED", buyerId);
        jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", orderId);

        OrderForReturn found = reader.findForBuyer(orderId, buyerId).orElseThrow();

        assertThat(found.delivered()).isTrue();
        assertThat(found.deliveredAt()).isNull();
    }

    @Test
    void anotherBuyersOrAMissingOrderLooksTheSame() {
        long orderId = order("DELIVERED", otherBuyerId);

        assertThat(reader.findForBuyer(orderId, buyerId)).isEmpty();
        assertThat(reader.findForBuyer(987654L, buyerId)).isEmpty();
    }

    @Test
    void theBuyersOrdersComeNewestFirstAndOnlyTheirOwn() {
        long first = order("DELIVERED", buyerId);
        long second = order("CONFIRMED", buyerId);
        order("DELIVERED", otherBuyerId);
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", DELIVERED.minusDays(5), first);
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", DELIVERED, second);
        historyDeliveredAt(first, DELIVERED.minusDays(4));

        List<OrderForReturn> orders = reader.findByBuyer(buyerId);

        assertThat(orders).extracting(OrderForReturn::orderId).containsExactly(second, first);
        assertThat(orders.get(1).deliveredAt()).isEqualTo(DELIVERED.minusDays(4));
        assertThat(orders.get(0).delivered()).isFalse();
        assertThat(reader.findByBuyer(otherBuyerId + 1000)).isEmpty();
    }
}
