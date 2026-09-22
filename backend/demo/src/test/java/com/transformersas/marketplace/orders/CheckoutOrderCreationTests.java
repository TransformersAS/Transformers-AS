package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D1/D3/D4: el checkout crea el pedido con tienda, snapshot de entrega y pago aprobado. */
class CheckoutOrderCreationTests extends AbstractIntegrationTest {

    @Autowired OrderRepository orders;

    private Session buyer;

    private void addToCart(long productId, int quantity) throws Exception {
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private List<Long> reserveCart() throws Exception {
        String body = perform(buyer, post("/api/reservations/cart")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).valueStream().map(node -> node.get("id").asLong()).toList();
    }

    private org.springframework.test.web.servlet.ResultActions pay(List<Long> reservations, long addressId)
            throws Exception {
        return perform(buyer, post("/api/payments/process").contentType("application/json").content("""
                {"paymentMethod":"CARD","reservationIds":%s,"addressId":%d,"shippingMethod":"STANDARD"}"""
                .formatted(reservations, addressId)));
    }

    @Test
    void approvedCheckoutCreatesOrderWithStoreSnapshotPaymentStatusAndInitialHistory() throws Exception {
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        long product = seedProduct(1, "Lámpara", 10, "100.00");
        long address = seedAddress(buyer);
        addToCart(product, 2);

        JsonNode payment = json.readTree(pay(reserveCart(), address).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn().getResponse().getContentAsString());
        long orderId = payment.get("orderId").asLong();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(row).containsEntry("store_id", 1L).containsEntry("status", "CONFIRMED")
                .containsEntry("payment_status", "APPROVED").containsEntry("shipping_method", "STANDARD")
                .containsEntry("delivery_recipient_name", "Ana Comprador")
                .containsEntry("delivery_street", "Calle 1 # 2-3").containsEntry("delivery_city", "Bogotá")
                .containsEntry("delivery_department", "Cundinamarca").containsEntry("delivery_postal_code", "110111")
                .containsEntry("delivery_phone", "+57 300 123-4567");
        assertThat(row.get("account_id")).isEqualTo(
                jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", Long.class, "buyer@example.com"));

        Map<String, Object> history = jdbc.queryForMap("SELECT * FROM order_status_history WHERE order_id = ?", orderId);
        assertThat(history).containsEntry("to_status", "CONFIRMED").containsEntry("actor_type", "BUYER");
        assertThat(history.get("from_status")).isNull();
        assertThat((String) history.get("correlation_id")).isNotBlank();

        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product)).isEqualTo(8);
        assertThat(count("cart_items")).isZero();
    }

    @Test
    void editingTheAddressAfterPurchaseDoesNotChangeTheDeliverySnapshot() throws Exception {
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        long product = seedProduct(1, "Lámpara", 10, "100.00");
        long address = seedAddress(buyer);
        addToCart(product, 1);
        long orderId = json.readTree(pay(reserveCart(), address).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("orderId").asLong();

        jdbc.update("UPDATE addresses SET street = 'Otra calle 99', city = 'Medellín', phone = '000' WHERE id = ?", address);

        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.delivery().street()).isEqualTo("Calle 1 # 2-3");
        assertThat(order.delivery().city()).isEqualTo("Bogotá");
        assertThat(order.delivery().phone()).isEqualTo("+57 300 123-4567");
        assertThat(order.addressId()).isEqualTo(address);
    }

    @Test
    void cartWithProductsFromSeveralStoresIsRejectedWith409AndNothingChanges() throws Exception {
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        seedStore(2, "Otra tienda");
        long first = seedProduct(1, "De tienda 1", 10, "100.00");
        long second = seedProduct(2, "De tienda 2", 10, "50.00");
        long address = seedAddress(buyer);
        addToCart(first, 1);
        addToCart(second, 1);
        List<Long> reservations = reserveCart();

        pay(reservations, address).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MULTI_STORE_CART"))
                .andExpect(jsonPath("$.status").value(409));

        assertThat(count("orders")).isZero();
        assertThat(count("order_status_history")).isZero();
        // La transacción completa se revirtió: el stock no se descontó y el carrito sigue intacto.
        assertThat(jdbc.queryForList("SELECT stock FROM products ORDER BY id", Integer.class)).containsExactly(10, 10);
        assertThat(count("cart_items")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM inventory_reservations", String.class))
                .containsExactly("ACTIVE");
    }
}
