package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-16 en la compra: los cupones del vendedor descuentan en el checkout de CU-03 a través de CheckoutCouponAspect,
 * sin tocar CheckoutService. Si alguien cambia la firma de preview() y el aspecto deja de aplicarse, estas pruebas
 * fallan.
 */
class CheckoutCouponTests extends AbstractIntegrationTest {

    private Session buyer;
    private long address;
    private long lamp;
    private long table;

    @BeforeEach
    void setUp() throws Exception {
        // La limpieza común conserva la tienda 1; sus cupones se borran aquí.
        jdbc.update("DELETE FROM promotions");
        seedStore(2, "Otra tienda");
        lamp = seedProduct(1, "Lámpara", 10, "50000");
        table = seedProduct(1, "Mesa", 10, "100000");
        buyer = sessionWithRole("comprador.cupon@example.com", "COMPRADOR");
        address = seedAddress(buyer);
    }

    // ---------- Ayudas ----------

    private void addToCart(long productId, int quantity) throws Exception {
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private ResultActions preview(String code) throws Exception {
        return perform(buyer, post("/api/checkout/preview").contentType("application/json").content("""
                {"addressId":%d,"shippingMethod":"STANDARD","couponCode":"%s"}""".formatted(address, code)));
    }

    /** Cupón activo y vigente, insertado directo en BD para aislar la prueba del checkout. */
    private long coupon(long storeId, String code, String type, String value, String minPurchase, Long... products) {
        jdbc.update("""
                INSERT INTO promotions(store_id, name, code, discount_type, discount_value, min_purchase, starts_at,
                                       ends_at, active, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,TRUE,NOW(6),NOW(6))""", storeId, "Cupón " + code, code, type, value,
                minPurchase, Timestamp.valueOf(LocalDateTime.now().minusDays(1)),
                Timestamp.valueOf(LocalDateTime.now().plusDays(1)));
        long id = jdbc.queryForObject("SELECT id FROM promotions WHERE store_id = ? AND code = ?", Long.class,
                storeId, code);
        for (Long product : products) {
            jdbc.update("INSERT INTO promotion_products(promotion_id, product_id) VALUES (?,?)", id, product);
        }
        return id;
    }

    // ---------- Vista previa ----------

    @Test
    void unCuponPorcentualDescuentaSobreElSubtotal() throws Exception {
        addToCart(lamp, 2);
        coupon(1, "VERANO20", "PERCENT", "20", null);

        // 2 × 50.000 = 100.000; 20 % = 20.000; envío estándar 10.000.
        preview("verano20").andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(100000))
                .andExpect(jsonPath("$.discount").value(20000))
                .andExpect(jsonPath("$.shippingCost").value(10000))
                .andExpect(jsonPath("$.total").value(90000))
                .andExpect(jsonPath("$.couponValid").value(true));
    }

    @Test
    void soloDescuentaLosProductosIncluidos() throws Exception {
        addToCart(lamp, 1);
        addToCart(table, 1);
        coupon(1, "MESA10", "PERCENT", "10", null, table);

        // Solo la mesa: 10 % de 100.000.
        preview("MESA10").andExpect(jsonPath("$.subtotal").value(150000))
                .andExpect(jsonPath("$.discount").value(10000))
                .andExpect(jsonPath("$.total").value(150000));
    }

    @Test
    void unMontoFijoNoSuperaLoQueAplica() throws Exception {
        addToCart(lamp, 1);
        coupon(1, "GRANDE", "AMOUNT", "80000", null);

        preview("GRANDE").andExpect(jsonPath("$.discount").value(50000))
                .andExpect(jsonPath("$.total").value(10000));
    }

    @Test
    void respetaLaCompraMinima() throws Exception {
        addToCart(lamp, 1);
        coupon(1, "MINIMO", "AMOUNT", "5000", "60000");

        preview("MINIMO").andExpect(jsonPath("$.discount").value(0))
                .andExpect(jsonPath("$.couponValid").value(false));
        addToCart(lamp, 1);
        preview("MINIMO").andExpect(jsonPath("$.discount").value(5000))
                .andExpect(jsonPath("$.couponValid").value(true));
    }

    @Test
    void noAplicaCuponesInactivosVencidosNiDeOtraTienda() throws Exception {
        addToCart(lamp, 1);
        long inactive = coupon(1, "APAGADO", "PERCENT", "10", null);
        jdbc.update("UPDATE promotions SET active = FALSE WHERE id = ?", inactive);
        long expired = coupon(1, "VIEJO", "PERCENT", "10", null);
        jdbc.update("UPDATE promotions SET starts_at = ?, ends_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(5)),
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), expired);
        coupon(2, "AJENO", "PERCENT", "10", null);

        for (String code : List.of("APAGADO", "VIEJO", "AJENO", "NOEXISTE")) {
            preview(code).andExpect(status().isOk())
                    .andExpect(jsonPath("$.discount").value(0))
                    .andExpect(jsonPath("$.couponValid").value(false));
        }
    }

    @Test
    void elCuponDePruebaDesc10SigueFuncionandoIgual() throws Exception {
        addToCart(lamp, 2);

        preview("DESC10").andExpect(jsonPath("$.discount").value(10000))
                .andExpect(jsonPath("$.total").value(100000))
                .andExpect(jsonPath("$.couponValid").value(true));
    }

    // ---------- Compra de punta a punta ----------

    @Test
    void elPedidoPagadoGuardaElDescuentoDelCupon() throws Exception {
        addToCart(lamp, 2);
        coupon(1, "PAGO15", "PERCENT", "15", null);
        String reservations = perform(buyer, post("/api/reservations/cart")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(reservations).valueStream().map(node -> node.get("id").asLong()).toList();

        String payment = perform(buyer, post("/api/payments/process").contentType("application/json").content("""
                {"paymentMethod":"CARD","reservationIds":%s,"addressId":%d,"shippingMethod":"STANDARD",
                 "couponCode":"pago15"}""".formatted(ids, address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long orderId = json.readTree(payment).get("orderId").asLong();

        // 100.000 − 15 % + 10.000 de envío.
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM orders WHERE id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("15000");
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("95000");
    }
}
