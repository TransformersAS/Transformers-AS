package com.transformersas.marketplace.demo;

import com.transformersas.marketplace.address.Address;
import com.transformersas.marketplace.address.AddressRepository;
import com.transformersas.marketplace.cart.CartService;
import com.transformersas.marketplace.cart.dto.AddCartItemRequest;
import com.transformersas.marketplace.payments.application.usecase.ProcessPaymentUseCase;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.reservation.InventoryReservationService;
import com.transformersas.marketplace.stores.application.usecase.AssignStoreOwnerUseCase;
import com.transformersas.marketplace.users.domain.model.*;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Preparación transaccional exclusiva de la base de datos de demostración. Sin endpoints adicionales. */
@Component
@Profile("demo")
public class DemoProvisioning implements ApplicationRunner {
    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final AssignStoreOwnerUseCase owners;
    private final ProductRepository products;
    private final AddressRepository addresses;
    private final CartService carts;
    private final InventoryReservationService reservations;
    private final ProcessPaymentUseCase payments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DemoProvisioning(UserAccountRepository accounts, PasswordEncoder encoder, AssignStoreOwnerUseCase owners,
            ProductRepository products, AddressRepository addresses, CartService carts,
            InventoryReservationService reservations, ProcessPaymentUseCase payments,
            JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.accounts = accounts; this.encoder = encoder; this.owners = owners; this.products = products;
        this.addresses = addresses; this.carts = carts; this.reservations = reservations;
        this.payments = payments; this.jdbc = jdbc; this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(tx -> {
            // Serializa incluso dos arranques simultáneos. Flyway crea este marcador solo en demo.
            Long orderId = jdbc.queryForObject(
                    "SELECT order_id FROM demo_fixture WHERE fixture_key = 'cu11' FOR UPDATE", Long.class);
            var seller = account("demo@marketplace.local", Set.of(Role.COMPRADOR, Role.VENDEDOR));
            var buyer = account("comprador.demo@example.com", Set.of(Role.COMPRADOR));
            owners.execute(1L, seller.id());
            if (orderId == null) {
                if (!carts.getCart(buyer.id()).items().isEmpty()) {
                    throw new IllegalStateException("El carrito de la cuenta demo debe estar vacío al crear el fixture");
                }
                Product product = products.save(new Product(null, "Camiseta demostración CU-11",
                        "Producto de la entrega académica", new BigDecimal("25000.00"), 100, "Ropa", true));
                Address address = new Address();
                address.setAccountId(buyer.id()); address.setRecipientName("Comprador demo");
                address.setStreet("Calle Demo 123"); address.setCity("Bogotá");
                address.setDepartment("Bogotá D.C."); address.setPhone("3333333333");
                address = addresses.save(address);
                carts.addItem(buyer.id(), new AddCartItemRequest(product.getId(), 1));
                var ids = reservations.reserveCart(buyer.id()).stream().map(r -> r.id()).toList();
                var result = payments.execute(buyer.id(), "CARD", ids, address.getId(), "STANDARD", null);
                if (result.order() == null) throw new IllegalStateException("No se aprobó la compra demo");
                jdbc.update("UPDATE demo_fixture SET order_id = ? WHERE fixture_key = 'cu11'", result.order().orderId());
            } else {
                restore(orderId, buyer.id());
            }
        });
    }

    private UserAccount account(String email, Set<Role> roles) {
        var existing = accounts.findByEmail(email);
        var account = existing.orElseGet(() -> accounts.save(new UserAccount(null, email,
                encoder.encode("MarketplaceDemo123!"), AccountStatus.ACTIVA, roles)));
        if (account.status() != AccountStatus.ACTIVA || !account.roles().equals(roles)
                || !encoder.matches("MarketplaceDemo123!", account.passwordHash())) {
            throw new IllegalStateException("La cuenta demo existente tiene una configuración diferente: " + email);
        }
        accounts.markEmailVerified(account.id());
        return account;
    }

    private void restore(long orderId, long buyerId) {
        var rows = jdbc.queryForList("SELECT status FROM orders WHERE id = ? AND account_id = ? FOR UPDATE",
                orderId, buyerId);
        if (rows.size() != 1) throw new IllegalStateException("El pedido demo no pertenece a la cuenta esperada");
        String state = (String) rows.getFirst().get("status");
        if (state.equals("CONFIRMED")) return;
        if (!state.equals("CANCELLED") || jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId) != 0) {
            throw new IllegalStateException("El pedido demo fue despachado o modificado fuera del guion; no se restaura");
        }
        // Deshace únicamente la reposición de esta cancelación, nunca fija el stock global a 100.
        for (var item : jdbc.queryForList("SELECT product_id, quantity FROM order_items WHERE order_id = ?", orderId)) {
            int changed = jdbc.update("UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?",
                    item.get("quantity"), item.get("product_id"), item.get("quantity"));
            if (changed != 1) throw new IllegalStateException("No hay inventario para restaurar el pedido demo");
        }
        jdbc.update("DELETE FROM refunds WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_cancellations WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM notifications WHERE reference_type = 'ORDER' AND reference_id = ?", "" + orderId);
        jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", orderId);
        jdbc.update("UPDATE orders SET status = 'CONFIRMED', payment_status = 'APPROVED' WHERE id = ?", orderId);
        jdbc.update("""
                INSERT INTO order_status_history
                (order_id, to_status, actor_type, reason, correlation_id, created_at)
                VALUES (?, 'CONFIRMED', 'SYSTEM', 'Restauración del fixture académico', 'demo-reset', NOW(6))
                """, orderId);
        // La auditoría histórica se conserva. Esto es restauración de un fixture, no una transición comercial.
    }
}
