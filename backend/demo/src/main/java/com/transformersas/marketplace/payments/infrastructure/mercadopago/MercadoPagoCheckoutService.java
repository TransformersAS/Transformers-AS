package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.address.AddressRepository;
import com.transformersas.marketplace.cart.*;
import com.transformersas.marketplace.checkout.CheckoutService;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.reservation.*;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.web.CorrelationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Durable snapshot: browser return parameters never approve a payment. */
@Service
public class MercadoPagoCheckoutService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MercadoPagoClient provider;
    private final CheckoutService checkout;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final AddressRepository addresses;
    private final InventoryReservationService reservations;
    private final InventoryReservationRepository reservationRows;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;

    public MercadoPagoCheckoutService(JdbcTemplate jdbc, ObjectMapper json, MercadoPagoClient provider,
            CheckoutService checkout, CartRepository carts, CartItemRepository cartItems,
            AddressRepository addresses, InventoryReservationService reservations,
            InventoryReservationRepository reservationRows, ProductRepository products,
            OrderRepository orders, OrderStatusHistoryRepository history) {
        this.jdbc = jdbc; this.json = json; this.provider = provider; this.checkout = checkout;
        this.carts = carts; this.cartItems = cartItems; this.addresses = addresses;
        this.reservations = reservations; this.reservationRows = reservationRows;
        this.products = products; this.orders = orders; this.history = history;
    }

    public record Purchase(Long addressId, String shippingMethod, String couponCode) { }
    public record Snapshot(Long storeId, Long addressId, String shippingMethod, String couponCode, DeliverySnapshot delivery,
                           List<OrderItem> items, List<Long> reservationIds, Map<Long, Integer> cartQuantities) { }
    public record CheckoutStatus(String id, String status, String checkoutUrl, Long orderId, BigDecimal total) { }
    private record Attempt(String id, Long accountId, Snapshot snapshot, BigDecimal total, String status,
                           String url, String paymentId, Long orderId, LocalDateTime expiresAt) {
        CheckoutStatus view() { return new CheckoutStatus(id, status, url, orderId, total); }
    }

    @Transactional
    public CheckoutStatus start(Long accountId, Purchase request) {
        provider.requireConfigured();
        if (request == null || request.addressId() == null)
            throw new ResponseStatusException(BAD_REQUEST, "Selecciona una dirección de entrega");
        var cart = carts.findByAccountId(accountId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "El carrito está vacío"));
        jdbc.queryForList("SELECT id FROM carts WHERE id=? FOR UPDATE", cart.getId());
        var open = jdbc.queryForList("""
                SELECT id FROM payment_checkouts WHERE account_id=?
                AND status IN ('PENDING','STARTING','REJECTED') AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """, accountId, LocalDateTime.now());
        if (!open.isEmpty()) {
            var previous = load((String) open.getFirst().get("id"), false);
            var current = cartItems.findByCartId(cart.getId());
            boolean same = Objects.equals(previous.snapshot.addressId(), request.addressId())
                    && Objects.equals(previous.snapshot.shippingMethod(), request.shippingMethod())
                    && Objects.equals(previous.snapshot.couponCode(), request.couponCode())
                    && current.size() == previous.snapshot.cartQuantities().size()
                    && current.stream().allMatch(item -> Objects.equals(previous.snapshot.cartQuantities().get(item.getId()), item.getQuantity()));
            if (!same) throw new ResponseStatusException(CONFLICT,
                    "Existe una compra pendiente con otros datos. Termina ese pago o espera a que venza su reserva antes de iniciar otra compra.");
            return previous.view();
        }
        var preview = checkout.preview(accountId, new CheckoutPreviewRequest(
                request.addressId(), request.shippingMethod(), request.couponCode()));
        var items = cartItems.findByCartId(cart.getId());
        if (items.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "El carrito está vacío");
        Set<Long> stores = new HashSet<>();
        items.forEach(item -> stores.add(item.getProduct().getStoreId()));
        if (stores.size() != 1 || stores.contains(null))
            throw new ResponseStatusException(CONFLICT, "Compra productos de una sola tienda por operación");
        Long storeId = stores.iterator().next();
        items.stream().map(item -> item.getProduct().getId()).sorted().forEach(id ->
                products.findForUpdate(id, storeId).orElseThrow(() -> new ResponseStatusException(CONFLICT)));
        var held = reservations.reserveCart(accountId);
        var address = addresses.findByIdAndAccountId(request.addressId(), accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Dirección no encontrada"));
        var lines = items.stream().map(item -> new OrderItem(item.getProduct().getId(),
                item.getProduct().getName(), item.getQuantity(), item.getProduct().getPrice(),
                item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))).toList();
        Map<Long, Integer> quantities = new HashMap<>();
        items.forEach(item -> quantities.put(item.getId(), item.getQuantity()));
        var snapshot = new Snapshot(storeId, address.getId(), request.shippingMethod(), request.couponCode(),
                new DeliverySnapshot(address.getRecipientName(), address.getStreet(), address.getCity(),
                        address.getDepartment(), address.getPostalCode(), address.getPhone()),
                lines, held.stream().map(r -> r.id()).toList(), quantities);
        String id = UUID.randomUUID().toString();
        LocalDateTime expiry = held.stream().map(r -> r.expiresAt()).min(LocalDateTime::compareTo).orElseThrow();
        jdbc.update("""
                INSERT INTO payment_checkouts(id, account_id, snapshot_json, total, status,
                expires_at, created_at, updated_at) VALUES (?,?,?,?,'STARTING',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, accountId, json.writeValueAsString(snapshot), preview.total(), expiry);
        var preference = provider.create(id, preview.total(), expiry.atZone(ZoneId.systemDefault())
                .toOffsetDateTime().toString());
        jdbc.update("""
                UPDATE payment_checkouts SET preference_id=?, checkout_url=?, status='PENDING',
                updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, preference.id(), preference.checkoutUrl(), id);
        return load(id, false).view();
    }

    @Transactional(readOnly = true)
    public CheckoutStatus status(Long accountId, String id) {
        Attempt attempt = load(id, false);
        if (!attempt.accountId.equals(accountId)) throw new ResponseStatusException(NOT_FOUND);
        return attempt.view();
    }

    @Transactional
    public CheckoutStatus reconcile(Long accountId, String id, String paymentId) {
        Attempt attempt = load(id, true);
        if (accountId != null && !attempt.accountId.equals(accountId)) throw new ResponseStatusException(NOT_FOUND);
        JsonNode payment = provider.payment(paymentId);
        if (!provider.matches(payment, id, attempt.total))
            throw new ResponseStatusException(CONFLICT, "El pago no corresponde a esta compra de pruebas");
        if (attempt.orderId != null || "REVIEW".equals(attempt.status)) return attempt.view();
        String result = payment.path("status").asString("");
        if ("approved".equals(result)) {
            var snapshot = attempt.snapshot;
            snapshot.items().stream().map(OrderItem::productId).sorted().forEach(productId ->
                    products.findForUpdate(productId, snapshot.storeId()));
            var rows = reservationRows.findByAccountIdAndIdIn(attempt.accountId, snapshot.reservationIds());
            boolean valid = rows.size() == snapshot.reservationIds().size() && rows.stream().allMatch(r ->
                    r.getStatus() == ReservationStatus.ACTIVE && r.getExpiresAt().isAfter(LocalDateTime.now())
                    && r.getProduct().getStock() >= r.getQuantity());
            if (!valid) {
                update(attempt.id, "REVIEW", paymentId, null);
                return load(id, false).view();
            }
            reservations.confirmReservations(attempt.accountId, snapshot.reservationIds());
            LocalDateTime now = LocalDateTime.now();
            var order = orders.save(new Order(null, attempt.accountId, OrderStatus.CONFIRMED,
                    OrderPaymentStatus.APPROVED, attempt.total, snapshot.storeId(), snapshot.addressId(),
                    snapshot.shippingMethod(), snapshot.delivery(), "MP-" + paymentId, now, snapshot.items()));
            history.append(new OrderStatusHistoryEntry(null, order.id(), null, OrderStatus.CONFIRMED,
                    ActorType.SYSTEM, null, "Pago de pruebas verificado con Mercado Pago", CorrelationContext.current(), now));
            // Preserve any items edited/added while the buyer was at the hosted checkout.
            snapshot.cartQuantities().forEach((itemId, quantity) -> cartItems.findByIdAndCart_AccountId(itemId,
                    attempt.accountId).filter(item -> item.getQuantity().equals(quantity)).ifPresent(cartItems::delete));
            update(id, "APPROVED", paymentId, order.id());
        } else {
            update(id, switch (result) {
                case "rejected", "cancelled" -> "REJECTED";
                case "refunded", "charged_back" -> "REVIEW";
                default -> "PENDING";
            }, paymentId, null);
        }
        return load(id, false).view();
    }

    @Transactional
    public void notification(String paymentId) {
        JsonNode payment = provider.payment(paymentId);
        String reference = payment.path("external_reference").asString("");
        if (!reference.matches("[a-f0-9-]{36}") || jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_checkouts WHERE id=?", Integer.class, reference) == 0) return;
        reconcile(null, reference, paymentId);
    }

    private void update(String id, String status, String paymentId, Long orderId) {
        jdbc.update("UPDATE payment_checkouts SET status=?,payment_id=?,order_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, paymentId, orderId, id);
    }

    private Attempt load(String id, boolean lock) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(NOT_FOUND);
        var rows = jdbc.query("SELECT * FROM payment_checkouts WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Attempt(r.getString("id"), r.getLong("account_id"),
                        json.readValue(r.getString("snapshot_json"), Snapshot.class), r.getBigDecimal("total"),
                        r.getString("status"), r.getString("checkout_url"), r.getString("payment_id"),
                        r.getObject("order_id", Long.class), r.getTimestamp("expires_at").toLocalDateTime()), id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return rows.getFirst();
    }
}
