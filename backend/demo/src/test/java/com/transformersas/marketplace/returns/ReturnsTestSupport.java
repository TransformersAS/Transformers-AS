package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Utilidades de las pruebas de devoluciones (CU-19). Las tablas de devoluciones se limpian antes y después de cada
 * prueba, porque la lista compartida de {@link AbstractIntegrationTest} no las incluye y sus filas referencian pedidos.
 */
abstract class ReturnsTestSupport extends AbstractIntegrationTest {
    static final List<String> RETURN_TABLES = List.of("claim_messages", "claim_evidences", "claims",
            "return_evidence_files", "return_events", "return_information_requests", "return_requests");

    /** Un pedido entregado de una sola línea. */
    record DeliveredOrder(long orderId, long itemId, long productId, long buyerId) {
    }

    @BeforeEach
    @AfterEach
    void cleanReturnTables() {
        RETURN_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    /** Pedido ENTREGADO de la tienda 1 (2 unidades a 10.00) que consta entregado en {@code deliveredAt}. */
    DeliveredOrder deliveredOrder(long buyerId, LocalDateTime deliveredAt) {
        long product = seedProduct(1, "Lámpara " + System.nanoTime(), 5, "10.00");
        long order = seedOrder(1, "DELIVERED", product, 2, "10.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, order);
        jdbc.update("UPDATE order_status_history SET created_at = ? WHERE order_id = ? AND to_status = 'DELIVERED'",
                deliveredAt, order);
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, order);
        return new DeliveredOrder(order, item, product, buyerId);
    }

    ReturnLine lineOf(DeliveredOrder order) {
        return new ReturnLine(order.itemId(), order.productId(), "Lámpara", 2, new BigDecimal("10.00"));
    }
}
