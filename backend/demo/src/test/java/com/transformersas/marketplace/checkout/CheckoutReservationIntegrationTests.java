package com.transformersas.marketplace.checkout;

import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.reservation.InventoryReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class CheckoutReservationIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("checkout_reservation_test").withUsername("test").withPassword("test");
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired InventoryReservationService reservations;
    long addressId;
    long cartId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM cart_items");
        jdbc.update("DELETE FROM carts");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM addresses");
        jdbc.update("INSERT INTO addresses(recipient_name,street,city,department,phone) VALUES ('Ana','Calle 1','Bogota','Bogota','3001234567')");
        addressId = jdbc.queryForObject("SELECT id FROM addresses", Long.class);
        jdbc.update("INSERT INTO carts () VALUES ()");
        cartId = jdbc.queryForObject("SELECT id FROM carts", Long.class);
    }

    @Test
    void reservesAllItemsUsingOnlyUnexpiredActiveReservationsAndMapsResponse() {
        long first = item("Primero", "12.55", 5, 2);
        long second = item("Segundo", "30.00", 1, 1);
        reservation(first, 3, "ACTIVE", 5);
        long expired = reservation(first, 50, "ACTIVE", -5);
        reservation(first, 50, "RELEASED", 5);
        reservation(first, 50, "CONFIRMED", 5);
        var result = reservations.reserveCart();
        assertThat(result).hasSize(2);
        var response = result.stream().filter(r -> r.productId() == first).findFirst().orElseThrow();
        assertThat(response.productName()).isEqualTo("Primero");
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("ACTIVE");
        LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM inventory_reservations WHERE id=?", LocalDateTime.class, response.id());
        LocalDateTime expiry = jdbc.queryForObject("SELECT expires_at FROM inventory_reservations WHERE id=?", LocalDateTime.class, response.id());
        assertThat(expiry).isEqualTo(created.plusMinutes(10));
        assertThat(response.expiresAt()).isAfter(created.plusMinutes(9)).isBefore(created.plusMinutes(11));
        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(stock(first)).isEqualTo(5);
        assertThat(stock(second)).isEqualTo(1);
    }

    @Test
    void insufficientAvailabilityDoesNotCreatePartialReservations() {
        item("Disponible", "10", 10, 1);
        long scarce = item("Escaso", "20", 3, 2);
        reservation(scarce, 2, "ACTIVE", 5);
        error(reservations::reserveCart, 409, "Disponible: 1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
        assertThat(stock(scarce)).isEqualTo(3);
    }

    @Test
    void confirmsMultipleReservationsAndAllowsExactStockBoundary() {
        long first = item("Primero", "10", 2, 2);
        long second = item("Segundo", "20", 5, 1);
        var ids = reservations.reserveCart().stream().map(r -> r.id()).toList();
        reservations.confirmReservations(ids);
        assertThat(stock(first)).isZero();
        assertThat(stock(second)).isEqualTo(4);
        ids.forEach(id -> assertThat(status(id)).isEqualTo("CONFIRMED"));
        error(() -> reservations.confirmReservations(ids), 409, "ya no está activa");
        assertThat(stock(second)).isEqualTo(4);
    }

    @Test
    void insufficientStockOnConfirmationLeavesAllReservationsAndProductsUnchanged() {
        long first = item("Primero", "10", 5, 1);
        long second = item("Segundo", "20", 1, 1);
        long valid = reservation(first, 1, "ACTIVE", 5);
        long invalid = reservation(second, 2, "ACTIVE", 5);
        error(() -> reservations.confirmReservations(List.of(valid, invalid)), 409, "Stock insuficiente");
        assertThat(stock(first)).isEqualTo(5);
        assertThat(stock(second)).isEqualTo(1);
        assertThat(status(valid)).isEqualTo("ACTIVE");
        assertThat(status(invalid)).isEqualTo("ACTIVE");
    }

    @Test
    void missingReservationRejectsWholeConfirmation() {
        long product = item("Producto", "10", 3, 1);
        long id = reservation(product, 1, "ACTIVE", 5);
        error(() -> reservations.confirmReservations(List.of(id, Long.MAX_VALUE)), 404, "no existen");
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    void expiresOnlyOverdueActiveReservationsAndRejectsTheirConfirmation() {
        long product = item("Producto", "10", 3, 1);
        long expired = reservation(product, 1, "ACTIVE", -5);
        long future = reservation(product, 1, "ACTIVE", 5);
        long confirmed = reservation(product, 1, "CONFIRMED", -5);
        reservations.expireOldReservations();
        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(status(future)).isEqualTo("ACTIVE");
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        error(() -> reservations.confirmReservations(List.of(expired)), 409, "ya no está activa");
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    void releaseOnlyChangesActiveReservationsAndDoesNotChangeStock() {
        long product = item("Producto", "10", 3, 1);
        long active = reservation(product, 1, "ACTIVE", 5);
        long confirmed = reservation(product, 1, "CONFIRMED", 5);
        long expired = reservation(product, 1, "EXPIRED", -5);
        reservations.releaseReservations(List.of(active, confirmed, expired, Long.MAX_VALUE));
        reservations.releaseReservations(null);
        reservations.releaseReservations(List.of());
        assertThat(status(active)).isEqualTo("RELEASED");
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    void rejectsMissingAndEmptyCartInBothServices() {
        error(reservations::reserveCart, 400, "vacío");
        error(() -> checkout.preview(request("STANDARD", null)), 400, "vacío");
        jdbc.update("DELETE FROM carts");
        error(reservations::reserveCart, 400, "No existe un carrito");
        error(() -> checkout.preview(request("STANDARD", null)), 400, "No existe un carrito");
    }

    @Test
    void rejectsInactiveProductInBothServices() {
        long product = item("Inactivo", "10", 3, 1);
        jdbc.update("UPDATE products SET active=false WHERE id=?", product);
        error(reservations::reserveCart, 400, "no está disponible");
        error(() -> checkout.preview(request("STANDARD", null)), 400, "no está disponible");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations", Integer.class)).isZero();
    }

    @Test
    void checkoutRejectsInsufficientStock() {
        item("Escaso", "10", 0, 1);
        error(() -> checkout.preview(request("STANDARD", null)), 409, "No hay suficiente stock");
    }

    @Test
    void checkoutValidatesRequestAndAddressBeforeReadingCart() {
        error(() -> checkout.preview(null), 400, "solicitud de checkout");
        error(() -> checkout.preview(new CheckoutPreviewRequest(null, "STANDARD", null)), 400, "dirección de entrega");
        error(() -> checkout.preview(new CheckoutPreviewRequest(Long.MAX_VALUE, "STANDARD", null)), 404, "no existe");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  ", "DRONE"})
    void checkoutRejectsMissingOrUnknownShipping(String shipping) {
        item("Producto", "10", 1, 1);
        error(() -> checkout.preview(request(shipping, null)), 400,
                "DRONE".equals(shipping) ? "inválido" : "Debe seleccionar un método de envío");
    }

    @Test
    void checkoutAddsItemsAndRoundsDiscountHalfUpWithoutMutatingInventoryOrCart() {
        long product = item("Primero", "12.55", 2, 2);
        item("Segundo", "0.05", 1, 1);
        var result = checkout.preview(request(" express ", " desc10 "));
        assertThat(result.subtotal()).isEqualByComparingTo("25.15");
        assertThat(result.discount()).isEqualByComparingTo("2.52");
        assertThat(result.shippingCost()).isEqualByComparingTo("20000");
        assertThat(result.total()).isEqualByComparingTo("20022.63");
        assertThat(result.couponValid()).isTrue();
        assertThat(stock(product)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM cart_items", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations", Integer.class)).isZero();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  ", "UNKNOWN"})
    void checkoutAbsentAndInvalidCouponsDoNotDiscountOrBlockCheckout(String coupon) {
        item("Producto", "12.55", 1, 1);
        var result = checkout.preview(request(" standard ", coupon));
        assertThat(result.subtotal()).isEqualByComparingTo("12.55");
        assertThat(result.discount()).isEqualByComparingTo("0");
        assertThat(result.shippingCost()).isEqualByComparingTo("10000");
        assertThat(result.total()).isEqualByComparingTo("10012.55");
        assertThat(result.couponValid()).isEqualTo(!"UNKNOWN".equals(coupon));
    }

    private CheckoutPreviewRequest request(String shipping, String coupon) {
        return new CheckoutPreviewRequest(addressId, shipping, coupon);
    }
    private long item(String name, String price, int stock, int quantity) {
        jdbc.update("INSERT INTO products(name,price,stock,category,active) VALUES (?,?,?,'Test',true)", name, price, stock);
        long id = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES (?,?,?)", cartId, id, quantity);
        return id;
    }
    private long reservation(long product, int quantity, String status, int minutes) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO inventory_reservations(product_id,quantity,status,created_at,expires_at) VALUES (?,?,?,?,?)",
                product, quantity, status, now.minusMinutes(10), now.plusMinutes(minutes));
        return jdbc.queryForObject("SELECT MAX(id) FROM inventory_reservations", Long.class);
    }
    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE id=?", String.class, id);
    }
    private int stock(long id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id);
    }
    private static void error(Runnable action, int status, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(status);
            assertThat(ex.getReason()).contains(reason);
        });
    }
}
