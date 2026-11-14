package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H03: lectura de historial real en MySQL temporal, sin modificar sus escritores. */
class BuyerOrderHistoryTests extends AbstractIntegrationTest {
    private Session buyer;
    private long account, product;

    @BeforeEach void fixtures() throws Exception {
        buyer = sessionWithRole("history-buyer@example.test", "COMPRADOR");
        account = accountIdOf("history-buyer@example.test");
        product = seedProduct(1, "Producto original", 10, "100.00");
    }

    private long order(String state) {
        long id = seedOrder(1, state, product, 1, "100.00");
        jdbc.update("UPDATE orders SET account_id=? WHERE id=?", account, id);
        jdbc.update("DELETE FROM order_status_history WHERE order_id=?", id);
        return id;
    }

    private void event(long id, String from, String to, String correlation, String date) {
        jdbc.update("""
                INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,actor_id,reason,correlation_id,created_at)
                VALUES (?,?,?,'SELLER',123,'Motivo privado',?,?)
                """, id, from, to, correlation, date);
    }

    private JsonNode detail(long id) throws Exception {
        return json.readTree(perform(buyer, get("/api/orders/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test void directPurchaseIncludesInitialHistoryAndReadsHaveNoSideEffects() throws Exception {
        long id = checkoutOrder(buyer, product, 2);
        var tables = List.of("order_status_history", "audit_events", "notifications", "refunds", "inventory_reservations", "orders");
        var counts = tables.stream().map(this::count).toList();
        var inventory = jdbc.queryForList("SELECT stock FROM products ORDER BY id");
        var payments = jdbc.queryForList("SELECT total,payment_status FROM orders ORDER BY id");
        for (int i = 0; i < 2; i++) {
            var view = detail(id);
            assertThat(view.get("status").asString()).isEqualTo("CONFIRMED");
            assertThat(view.get("history").size()).isEqualTo(1);
            var entry = view.get("history").get(0);
            assertThat(entry.size()).isEqualTo(4);
            assertThat(entry.get("fromStatus").isNull()).isTrue();
            assertThat(entry.get("toStatus").asString()).isEqualTo("CONFIRMED");
            assertThat(entry.get("createdAt").isNull()).isFalse();
            assertThat(entry.get("legacy").asBoolean()).isFalse();
        }
        assertThat(tables.stream().map(this::count).toList()).isEqualTo(counts);
        assertThat(jdbc.queryForList("SELECT stock FROM products ORDER BY id")).isEqualTo(inventory);
        assertThat(jdbc.queryForList("SELECT total,payment_status FROM orders ORDER BY id")).isEqualTo(payments);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preparationThenDispatchOrCancellationAreVisible(boolean cancel) throws Exception {
        long id = checkoutOrder(buyer, product, 2);
        var seller = sellerOfStore("history-seller@example.test", 1);
        perform(seller, post("/api/seller/orders/{id}/start-preparation", id)).andExpect(status().isOk());
        if (cancel) {
            perform(buyer, post("/api/orders/{id}/cancellation", id).contentType("application/json")
                    .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andExpect(status().isOk());
        } else {
            perform(seller, post("/api/seller/orders/{id}/ready-for-dispatch", id)).andExpect(status().isOk());
        }
        var view = detail(id);
        assertThat(view.get("history").size()).isEqualTo(3);
        assertThat(view.get("history").get(1).get("fromStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(view.get("history").get(1).get("toStatus").asString()).isEqualTo("IN_PREPARATION");
        assertThat(view.get("history").get(2).get("fromStatus").asString()).isEqualTo("IN_PREPARATION");
        assertThat(view.get("history").get(2).get("toStatus").asString()).isEqualTo(cancel ? "CANCELLED" : "READY_FOR_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(cancel ? 10 : 8);
    }

    @Test void preservesRegistrationOrderEqualDatesAndRepeatedStatesWithoutLeakingMetadata() throws Exception {
        long id = order("DELIVERY_EXCEPTION");
        event(id, "IN_TRANSIT", "DELIVERY_EXCEPTION", "private-1", "2026-10-20 10:00:00");
        event(id, "DELIVERY_EXCEPTION", "IN_TRANSIT", "private-2", "2026-10-20 10:00:00");
        event(id, "IN_TRANSIT", "DELIVERY_EXCEPTION", "private-3", "2026-10-20 10:00:00");
        var history = detail(id).get("history");
        assertThat(history.valueStream().map(row -> row.get("toStatus").asString()).toList())
                .containsExactly("DELIVERY_EXCEPTION", "IN_TRANSIT", "DELIVERY_EXCEPTION");
        for (var entry : history) {
            assertThat(entry.size()).isEqualTo(4);
            for (String field : List.of("actorId", "actorType", "reason", "correlationId", "id", "orderId")) {
                assertThat(entry.has(field)).isFalse();
            }
        }
        assertThat(history.toString()).doesNotContain("private-", "Motivo privado");
    }

    @Test void emptyAndPartialHistoryDoNotInventMissingTransitions() throws Exception {
        long id = order("DELIVERED");
        assertThat(detail(id).get("history").size()).isZero();
        event(id, "PICKED_UP", "IN_TRANSIT", "partial", "2026-10-20 10:00:00");
        var view = detail(id);
        assertThat(view.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(view.get("history").size()).isEqualTo(1);
        assertThat(view.get("history").get(0).get("toStatus").asString()).isEqualTo("IN_TRANSIT");
    }

    @Test void migrationEntryHasNoTransitionDateAndSubsequentRealEventsRemain() throws Exception {
        long id = order("IN_PREPARATION");
        event(id, null, "CONFIRMED", "migration-V14", "2026-01-01 10:00:00");
        event(id, "CONFIRMED", "IN_PREPARATION", "actual-change", "2026-10-20 10:00:00");
        var history = detail(id).get("history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("legacy").asBoolean()).isTrue();
        assertThat(history.get(0).get("createdAt").isNull()).isTrue();
        assertThat(history.get(1).get("legacy").asBoolean()).isFalse();
        assertThat(history.get(1).get("createdAt").asString()).isEqualTo("2026-10-20T10:00:00");
    }

    @Test void foreignAndMissingOrdersAreBoth404() throws Exception {
        long id = order("CONFIRMED");
        var other = sessionWithRole("other-history@example.test", "COMPRADOR");
        for (long target : List.of(id, Long.MAX_VALUE)) {
            perform(other, get("/api/orders/{id}", target)).andExpect(status().isNotFound());
            perform(other, head("/api/orders/{id}", target)).andExpect(status().isNotFound());
        }
    }
}
