package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher notices;
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
    private final StoreRepository stores;
    private final MercadoPagoPaymentJournal journal;
    private final MercadoPagoFinancialService financial;
    private final TransactionTemplate localTransaction;

    public MercadoPagoCheckoutService(JdbcTemplate jdbc, ObjectMapper json, MercadoPagoClient provider,
            CheckoutService checkout, CartRepository carts, CartItemRepository cartItems,
            AddressRepository addresses, InventoryReservationService reservations,
            InventoryReservationRepository reservationRows, ProductRepository products,
            OrderRepository orders, OrderStatusHistoryRepository history, StoreRepository stores,
            MercadoPagoPaymentJournal journal, PlatformTransactionManager transactions, MercadoPagoFinancialService financial, com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher notices) {
        this.notices = notices; this.jdbc = jdbc; this.json = json; this.provider = provider; this.checkout = checkout;
        this.carts = carts; this.cartItems = cartItems; this.addresses = addresses;
        this.reservations = reservations; this.reservationRows = reservationRows;
        this.products = products; this.orders = orders; this.history = history; this.stores = stores;
        this.journal = journal; this.financial = financial; this.localTransaction = new TransactionTemplate(transactions);
    }

    public record Purchase(Long addressId, String shippingMethod, String couponCode) { }
    public record Snapshot(Long storeId, Long addressId, String shippingMethod, String couponCode, DeliverySnapshot delivery,
                           List<OrderItem> items, List<Long> reservationIds, Map<Long, Integer> cartQuantities,
                           BigDecimal discountAmount, BigDecimal shippingCost, String sellerNameSnapshot) { }
    public record CheckoutStatus(String id, String status, String checkoutUrl, Long orderId, BigDecimal total, LocalDateTime cancelledAt, String financialStatus) { }
    private record Attempt(String id, Long accountId, Snapshot snapshot, BigDecimal total, String status,
                           String url, String paymentId, Long orderId, LocalDateTime expiresAt, LocalDateTime cancelledAt) {
        CheckoutStatus view() { return new CheckoutStatus(id, cancelledAt == null ? status : "CANCELLED", cancelledAt == null ? url : null, orderId, total, cancelledAt, null); }
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
                AND cancelled_at IS NULL AND status IN ('PENDING','STARTING','REJECTED') AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """, accountId, LocalDateTime.now());
        if (!open.isEmpty()) {
            var previous = load((String) open.getFirst().get("id"), true);
            var current = cartItems.findByCartId(cart.getId());
            boolean same = Objects.equals(previous.snapshot.addressId(), request.addressId())
                    && Objects.equals(previous.snapshot.shippingMethod(), request.shippingMethod())
                    && Objects.equals(previous.snapshot.couponCode(), request.couponCode())
                    && current.size() == previous.snapshot.cartQuantities().size()
                    && current.stream().allMatch(item -> Objects.equals(previous.snapshot.cartQuantities().get(item.getId()), item.getQuantity()));
            if (!same) throw new ResponseStatusException(CONFLICT,
                    "Existe una compra pendiente con otros datos. Termina ese pago o espera a que venza su reserva antes de iniciar otra compra.");
            return view(previous);
        }
        var preview = checkout.preview(accountId, new CheckoutPreviewRequest(
                request.addressId(), request.shippingMethod(), request.couponCode()));
        var items = cartItems.findByCartId(cart.getId());
        if (items.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "El carrito está vacío");
        Set<Long> storeIds = new HashSet<>();
        items.forEach(item -> storeIds.add(item.getProduct().getStoreId()));
        if (storeIds.size() != 1 || storeIds.contains(null))
            throw new ResponseStatusException(CONFLICT, "Compra productos de una sola tienda por operación");
        Long storeId = storeIds.iterator().next();
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
        String sellerName = stores.findById(storeId).orElseThrow(() ->
                new ResponseStatusException(NOT_FOUND, "La tienda no existe")).profile().name();
        var snapshot = new Snapshot(storeId, address.getId(), request.shippingMethod(), request.couponCode(),
                new DeliverySnapshot(address.getRecipientName(), address.getStreet(), address.getCity(),
                        address.getDepartment(), address.getPostalCode(), address.getPhone()),
                lines, held.stream().map(r -> r.id()).toList(), quantities, preview.discount(), preview.shippingCost(), sellerName);
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
        return view(load(id, false));
    }

    @Transactional(readOnly = true)
    public CheckoutStatus status(Long accountId, String id) {
        Attempt attempt = load(id, false);
        if (!attempt.accountId.equals(accountId)) throw new ResponseStatusException(NOT_FOUND);
        return view(attempt);
    }

    private CheckoutStatus view(Attempt attempt) {
        var base = attempt.view();
        return new CheckoutStatus(base.id(), base.status(), base.checkoutUrl(), base.orderId(), base.total(),
                attempt.cancelledAt, attempt.cancelledAt == null ? null : cancellationFinancialStatus(attempt.id));
    }

    public record CancellationResult(String id, LocalDateTime cancelledAt, String status,
                                     String financialStatus, String message) { }

    @Transactional
    public CancellationResult cancel(Long accountId, String id) {
        Attempt attempt = load(id, true);
        if (!attempt.accountId.equals(accountId)) throw new ResponseStatusException(NOT_FOUND);
        if (attempt.orderId != null) throw new ResponseStatusException(CONFLICT,
                "Ya existe un pedido para esta compra. Consulta Mis pedidos para solicitar su cancelación si corresponde.");
        if (attempt.cancelledAt == null) {
            // Mismo orden que materialize: checkout, productos ordenados, reservas.
            var snapshot = attempt.snapshot;
            snapshot.items().stream().map(OrderItem::productId).distinct().sorted().forEach(productId ->
                    products.findForUpdate(productId, snapshot.storeId()));
            reservations.releaseReservations(accountId, attempt.snapshot.reservationIds());
            jdbc.update("UPDATE payment_checkouts SET cancelled_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND cancelled_at IS NULL", id);
            attempt = load(id, true);
        }
        return new CancellationResult(id, attempt.cancelledAt, "CANCELLED", cancellationFinancialStatus(id),
                "El proceso de compra está cerrado y no generará un pedido. El cierre no confirma la cancelación de un pago externo ni su devolución; cualquier pago requiere verificación y resolución financiera.");
    }

    private String cancellationFinancialStatus(String id) {
        var payments = jdbc.queryForList("""
                SELECT p.approved_seen,p.verified_status,p.resolution_status,r.status AS refund_status,r.externally_confirmed
                FROM external_payment_observations p LEFT JOIN external_payment_refunds r ON r.payment_id=p.payment_id
                WHERE p.checkout_id=?
                """, id);
        if (payments.isEmpty()) return "REQUIRES_VERIFICATION";
        if (payments.stream().anyMatch(p -> "PAYMENT_MISMATCH".equals(p.get("resolution_status"))
                || "FAILED".equals(p.get("refund_status")) || "EXCEPTION".equals(p.get("refund_status"))))
            return "REQUIRES_REVIEW";
        if (payments.stream().anyMatch(p -> (Boolean.TRUE.equals(p.get("approved_seen"))
                || "refunded".equals(p.get("verified_status"))) && !Boolean.TRUE.equals(p.get("externally_confirmed"))))
            return "PENDING_RESOLUTION";
        if (payments.stream().allMatch(p -> Boolean.TRUE.equals(p.get("externally_confirmed")))) return "REFUNDED";
        return "REQUIRES_VERIFICATION";
    }

    public CheckoutStatus reconcile(Long accountId, String id, String paymentId) {
        Attempt attempt = load(id, false);
        if (accountId != null && !attempt.accountId.equals(accountId)) throw new ResponseStatusException(NOT_FOUND);
        return applyVerified(attempt, paymentId, provider.payment(paymentId), null);
    }

    CheckoutStatus recoverPayment(String id, String paymentId, String lease) {
        return applyVerified(load(id, false), paymentId, provider.payment(paymentId), lease);
    }

    private CheckoutStatus applyVerified(Attempt attempt, String paymentId, JsonNode payment, String lease) {
        boolean matches = provider.matches(payment, attempt.id, attempt.total);
        if (!paymentId.equals(payment.path("id").asString())
                || (!matches && !provider.belongsToCheckout(payment, attempt.id)))
            throw new ResponseStatusException(CONFLICT, "El pago no corresponde a esta compra de pruebas");
        if (!payment.path("transaction_amount").isNumber() || !payment.path("currency_id").asString().matches("[A-Z]{3}"))
            throw new ResponseStatusException(BAD_GATEWAY, "Datos verificados de pago incompletos");
        try {
            if (payment.path("transaction_amount").decimalValue().setScale(2, java.math.RoundingMode.UNNECESSARY).precision() > 19)
                throw new ArithmeticException("precision");
        } catch (ArithmeticException invalidAmount) {
            throw new ResponseStatusException(BAD_GATEWAY, "Importe verificado no representable sin pérdida");
        }
        // Commit independiente ANTES de consumir reservas: una falla local no borra el pago observado.
        journal.record(attempt.id, paymentId, payment, lease);
        try {
            CheckoutStatus result = localTransaction.execute(status -> {
                if (!matches) {
                    journal.requireLease(attempt.id, lease);
                    jdbc.update("UPDATE payment_checkouts SET status='REVIEW',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND order_id IS NULL", attempt.id);
                    journal.resolution(paymentId, "PAYMENT_MISMATCH", null);
                    return view(load(attempt.id, true));
                }
                return materialize(attempt.id, paymentId, payment, lease);
            });
            financial.observeVerified(paymentId, payment);
            return "refunded".equals(payment.path("status").asString()) ? view(load(attempt.id, false)) : result;
        } catch (RuntimeException failure) {
            try { journal.localFailure(paymentId, "LOCAL_COMMIT_FAILED"); }
            catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }

    private CheckoutStatus materialize(String id, String paymentId, JsonNode payment, String lease) {
        Attempt attempt = load(id, true);
        journal.requireLease(id, lease);
        if (attempt.cancelledAt != null) {
            if (financial.externallyConfirmed(paymentId)) return view(attempt);
            journal.resolution(paymentId, "approved".equals(payment.path("status").asString())
                    ? "REQUIRES_REVIEW" : "PENDING_PAYMENT", null);
            return view(attempt);
        }
        if (financial.externallyConfirmed(paymentId)) return view(attempt);
        if (attempt.orderId != null) {
            String transaction = jdbc.queryForObject("SELECT transaction_id FROM orders WHERE id=?", String.class, attempt.orderId);
            boolean linked = ("MP-" + paymentId).equals(transaction);
            String state = linked ? "ORDER_LINKED" : "PENDING_PAYMENT";
            if (!linked && "approved".equals(payment.path("status").asString())) state = "ADDITIONAL_PAYMENT";
            if (linked && ("refunded".equals(payment.path("status").asString())
                    || "charged_back".equals(payment.path("status").asString()))) state = "FINANCIAL_UPDATE_PENDING";
            journal.resolution(paymentId, state, linked ? attempt.orderId : null);
            return view(attempt);
        }
        if ("REVIEW".equals(attempt.status) || "REFUNDED".equals(attempt.status)) {
            if ("approved".equals(payment.path("status").asString()))
                update(id, "REVIEW", attempt.paymentId, null);
            journal.resolution(paymentId, "REQUIRES_REVIEW", null);
            return view(attempt);
        }
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
                journal.resolution(paymentId, "REQUIRES_REVIEW", null);
                return view(load(id, false));
            }
            reservations.confirmReservations(attempt.accountId, snapshot.reservationIds());
            LocalDateTime now = LocalDateTime.now();
            var order = orders.save(new Order(null, attempt.accountId, OrderStatus.CONFIRMED,
                    OrderPaymentStatus.APPROVED, attempt.total, snapshot.storeId(), snapshot.addressId(),
                    snapshot.shippingMethod(), snapshot.delivery(), "MP-" + paymentId, now, snapshot.items(),
                    snapshot.discountAmount(), snapshot.shippingCost(), snapshot.sellerNameSnapshot()));
            notices.purchased(order);
            history.append(new OrderStatusHistoryEntry(null, order.id(), null, OrderStatus.CONFIRMED,
                    ActorType.SYSTEM, null, "Pago de pruebas verificado con Mercado Pago", CorrelationContext.current(), now));
            // Preserve any items edited/added while the buyer was at the hosted checkout.
            snapshot.cartQuantities().forEach((itemId, quantity) -> cartItems.findByIdAndCart_AccountId(itemId,
                    attempt.accountId).filter(item -> item.getQuantity().equals(quantity)).ifPresent(cartItems::delete));
            update(id, "APPROVED", paymentId, order.id());
            journal.resolution(paymentId, "ORDER_LINKED", order.id());
        } else {
            update(id, switch (result) {
                case "rejected", "cancelled" -> "REJECTED";
                case "refunded", "charged_back" -> "REVIEW";
                default -> "PENDING";
            }, paymentId, null);
            journal.resolution(paymentId, ("refunded".equals(result) || "charged_back".equals(result))
                    ? "REQUIRES_REVIEW" : "PENDING_PAYMENT", null);
        }
        return view(load(id, false));
    }

    public void notification(String paymentId) {
        JsonNode payment = provider.payment(paymentId);
        String reference = payment.path("external_reference").asString("");
        if (!reference.matches("[a-f0-9-]{36}") || jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_checkouts WHERE id=?", Integer.class, reference) == 0) return;
        applyVerified(load(reference, false), paymentId, payment, null);
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
                        r.getObject("order_id", Long.class), r.getTimestamp("expires_at").toLocalDateTime(),
                        r.getTimestamp("cancelled_at") == null ? null : r.getTimestamp("cancelled_at").toLocalDateTime()), id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return rows.getFirst();
    }
}
