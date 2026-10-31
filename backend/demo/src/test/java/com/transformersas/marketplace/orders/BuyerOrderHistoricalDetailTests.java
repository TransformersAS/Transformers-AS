package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RF-027: API real, persistencia MySQL aislada y pasarelas simuladas. */
class BuyerOrderHistoricalDetailTests extends AbstractIntegrationTest {
    @Autowired OrderRepository orders;
    @Autowired SimulatedRefundGateway refunds;

    private Session buyer;
    private long product;
    private long address;

    private long purchase(String method, String coupon) throws Exception {
        refunds.reset();
        buyer = sessionWithRole("history@example.test", "COMPRADOR");
        product = seedProduct(1, "Producto original", 10, "100000.00");
        address = seedAddress(buyer);
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", product, "quantity", 2))))
                .andExpect(status().isCreated());
        var reserved = json.readTree(perform(buyer, post("/api/reservations/cart"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = reserved.valueStream().map(r -> r.get("id").asLong()).toList();
        var payment = json.readTree(perform(buyer, post("/api/payments/process").contentType("application/json")
                .content(json.writeValueAsString(Map.of("paymentMethod", "CARD", "reservationIds", ids,
                        "addressId", address, "shippingMethod", method, "couponCode", coupon))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString());
        return payment.get("orderId").asLong();
    }

    private JsonNode detail(long id) throws Exception {
        return json.readTree(perform(buyer, get("/api/orders/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,DESC10,20000,10000,190000", "EXPRESS,DESC10,20000,20000,200000",
            "STANDARD,'',0,10000,210000", "EXPRESS,INVALID,0,20000,220000"})
    void allTenFieldsSurviveChangesToCurrentData(String method, String coupon, String discount,
                                                String shipping, String total) throws Exception {
        long id = purchase(method, coupon);
        var before = orders.findById(id).orElseThrow();
        assertThat(before.discountAmount()).isEqualByComparingTo(discount);
        assertThat(before.shippingCost()).isEqualByComparingTo(shipping);
        assertThat(before.sellerNameSnapshot()).isEqualTo("Tienda principal");
        jdbc.update("UPDATE products SET name='Otro producto', price=1 WHERE id=?", product);
        jdbc.update("UPDATE addresses SET street='Otra dirección', city='Otra ciudad' WHERE id=?", address);
        jdbc.update("UPDATE stores SET name='Otro nombre comercial' WHERE id=1");

        var view = detail(id);
        assertThat(view.get("items").get(0).get("productName").asString()).isEqualTo("Producto original");
        assertThat(view.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(view.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("100000");
        assertThat(view.get("discountAmount").decimalValue()).isEqualByComparingTo(discount);
        assertThat(view.get("shippingCost").decimalValue()).isEqualByComparingTo(shipping);
        assertThat(view.get("total").decimalValue()).isEqualByComparingTo(total);
        assertThat(view.get("delivery").get("street").asString()).isEqualTo("Calle 1 # 2-3");
        assertThat(view.get("delivery").get("city").asString()).isEqualTo("Bogotá");
        assertThat(view.get("shippingMethod").asString()).isEqualTo(method);
        assertThat(view.get("paymentStatus").asString()).isEqualTo("APPROVED");
        assertThat(view.get("sellerNameSnapshot").asString()).isEqualTo("Tienda principal");
        assertThat(view.get("storeId").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(8);
        assertThat(count("cart_items")).isZero();
    }

    @Test
    void legacyNullsStayUnknownAndForeignAccountsCannotReadThem() throws Exception {
        long id = purchase("STANDARD", "");
        // Equivale a una fila creada antes de V34: la migración no rellena estos campos.
        jdbc.update("UPDATE orders SET discount_amount=NULL, shipping_cost=NULL, seller_name_snapshot=NULL WHERE id=?", id);
        var view = detail(id);
        for (String field : List.of("discountAmount", "shippingCost", "sellerNameSnapshot")) {
            assertThat(view.has(field)).isTrue();
            assertThat(view.get(field).isNull()).isTrue();
        }
        assertThat(view.get("total").decimalValue()).isEqualByComparingTo("210000");
        var other = sessionWithRole("foreign@example.test", "COMPRADOR");
        perform(other, get("/api/orders/{id}", id).param("accountId", accountIdOf("history@example.test").toString()))
                .andExpect(status().isNotFound());
        perform(other, head("/api/orders/{id}", id)).andExpect(status().isNotFound());
        perform(other, get("/api/orders")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/orders/{id}", id)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"OK,REFUNDED", "PENDING,REFUND_PENDING"})
    void cancellationKeepsHistoricalValuesAndRestoresInventoryOnlyOnce(String mode, String paymentStatus) throws Exception {
        long id = purchase("EXPRESS", "DESC10");
        var before = detail(id);
        refunds.setMode(SimulatedRefundGateway.Mode.valueOf(mode));
        try {
            perform(buyer, post("/api/orders/{id}/cancellation", id).contentType("application/json")
                    .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value(paymentStatus));
            var after = detail(id);
            assertThat(after.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(after.get("paymentStatus").asString()).isEqualTo(paymentStatus);
            for (String field : List.of("items", "delivery", "storeId", "sellerNameSnapshot", "total", "discountAmount", "shippingCost")) {
                assertThat(after.get(field)).isEqualTo(before.get(field));
            }
            perform(buyer, post("/api/orders/{id}/cancellation", id).contentType("application/json")
                    .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
            assertThat(count("refunds")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT amount FROM refunds WHERE order_id=?", BigDecimal.class, id))
                    .isEqualByComparingTo("200000");
            assertThat(refunds.requestCount()).isEqualTo(1);
        } finally {
            refunds.reset();
        }
    }
}
