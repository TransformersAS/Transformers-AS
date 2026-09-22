package com.transformersas.marketplace.checkout;

import com.transformersas.marketplace.address.AddressRepository;
import com.transformersas.marketplace.cart.*;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.product.*;
import com.transformersas.marketplace.reservation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// Repository doubles supply defensive states forbidden by database constraints.
class CheckoutReservationValidationTests {
    InventoryReservationRepository repository;
    ProductRepository products;
    InventoryReservationService reservations;
    CheckoutService checkout;
    Product product;
    CartItem item;

    @BeforeEach
    void setup() {
        repository = mock(InventoryReservationRepository.class);
        products = mock(ProductRepository.class);
        var carts = mock(CartRepository.class);
        var items = mock(CartItemRepository.class);
        var addresses = mock(AddressRepository.class);
        reservations = new InventoryReservationService(repository, carts, items, products);
        checkout = new CheckoutService(carts, items, addresses);
        var cart = new Cart();
        cart.setId(1L);
        product = new Product(1L, "Producto", null, new BigDecimal("10"), 5, "Test", true);
        item = new CartItem();
        item.setProduct(product);
        item.setQuantity(1);
        when(carts.findByAccountId(1L)).thenReturn(java.util.Optional.of(cart));
        when(items.findByCartId(1L)).thenReturn(List.of(item));
        when(addresses.existsByIdAndAccountId(1L, 1L)).thenReturn(true);
    }
    @Test
    void missingProductIsRejectedByBothServices() {
        item.setProduct(null);
        rejectsBoth(400, "ya no existe");
    }
    @Test
    void missingActiveFlagIsNotAvailable() {
        product.setActive(null);
        rejectsBoth(400, "no está disponible");
    }
    @ParameterizedTest @NullSource @ValueSource(ints = {0, -1})
    void invalidQuantityIsRejectedByBothServices(Integer quantity) {
        item.setQuantity(quantity);
        rejectsBoth(400, "Cantidad inválida");
    }
    @Test
    void missingStockIsRejectedByBothServices() {
        product.setStock(null);
        rejects(() -> reservations.reserveCart(1L), 409, "no tiene stock válido");
        rejects(() -> checkout.preview(1L, new CheckoutPreviewRequest(1L, "STANDARD", null)), 409, "No hay suficiente stock");
        verifyNoWrites();
    }
    @Test
    void negativeStockCannotBeReserved() {
        product.setStock(-1);
        rejects(() -> reservations.reserveCart(1L), 409, "no tiene stock válido");
        verifyNoWrites();
    }
    @Test
    void confirmationRequiresIds() {
        rejects(() -> reservations.confirmReservations(1L, null), 400, "Debe indicar las reservas");
        rejects(() -> reservations.confirmReservations(1L, List.of()), 400, "Debe indicar las reservas");
        verify(repository, never()).findByAccountIdAndIdIn(any(), any());
        verifyNoWrites();
    }
    @Test
    void confirmationRejectsMissingProduct() {
        var reservation = activeReservation();
        reservation.setProduct(null);
        rejects(() -> reservations.confirmReservations(1L, List.of(1L)), 409, "no existe");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        verifyNoWrites();
    }
    @Test
    void confirmationRejectsMissingStock() {
        var reservation = activeReservation();
        product.setStock(null);
        rejects(() -> reservations.confirmReservations(1L, List.of(1L)), 409, "Stock insuficiente");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        verifyNoWrites();
    }
    @Test
    void confirmationRechecksExpiryAfterCleanup() {
        var reservation = activeReservation();
        // Model expiry between the cleanup query and validation, without timing-dependent sleeps.
        reservation.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        rejects(() -> reservations.confirmReservations(1L, List.of(1L)), 409, "ha expirado");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(repository).save(reservation);
        verify(repository, never()).saveAll(any());
        verifyNoInteractions(products);
        assertThat(product.getStock()).isEqualTo(5);
    }
    private InventoryReservation activeReservation() {
        var reservation = new InventoryReservation();
        reservation.setProduct(product);
        reservation.setQuantity(1);
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(repository.findByAccountIdAndIdIn(1L, List.of(1L))).thenReturn(List.of(reservation));
        return reservation;
    }
    private void rejectsBoth(int status, String reason) {
        rejects(() -> reservations.reserveCart(1L), status, reason);
        rejects(() -> checkout.preview(1L, new CheckoutPreviewRequest(1L, "STANDARD", null)), status, reason);
        verifyNoWrites();
    }
    private void verifyNoWrites() {
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(any());
        verifyNoInteractions(products);
    }
    private static void rejects(Runnable action, int status, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(status);
            assertThat(ex.getReason()).contains(reason);
        });
    }
}
