package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.transformersas.marketplace.orders.domain.model.BuyerOrderFilters;

@Service
public class FindOwnOrders {
    private final OrderRepository orders;

    public FindOwnOrders(OrderRepository orders) { this.orders = orders; }

    public List<Order> list(AccountPrincipal principal) {
        return orders.findByAccountId(accountId(principal));
    }

    public List<Order> list(AccountPrincipal principal, String from, String to, String status) {
        Long accountId = accountId(principal);
        BuyerOrderFilters filters = BuyerOrderFilters.parse(from, to, status);
        return orders.findByAccountId(accountId, filters);
    }

    @Autowired private JdbcTemplate jdbc;

    public Order detail(Long id, AccountPrincipal principal) {
        Order order = orders.findByIdAndAccountId(id, accountId(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        if (jdbc != null && order.paymentStatus() == com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus.APPROVED
                && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM external_payment_observations p JOIN payment_checkouts c ON c.id=p.checkout_id
                    WHERE c.order_id=? AND p.order_id=c.order_id AND CONCAT('MP-',p.payment_id)=?
                      AND p.verified_status='approved' AND p.approved_seen=TRUE AND p.resolution_status='ORDER_LINKED'
                      AND p.amount=? AND p.currency='COP'
                    """, Integer.class, order.id(), order.transactionId(), order.total()) > 0)
            return order.withProvenance(order.provenance().withVerifiedUpdate());
        // Solo lectura y después de autorizar. Una referencia simulada no satisface esta evidencia.
        if (jdbc != null && order.paymentStatus() == com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus.REFUNDED
                && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM external_payment_refunds r
                    JOIN external_payment_observations p ON p.payment_id=r.payment_id
                    JOIN payment_checkouts c ON c.id=p.checkout_id
                    WHERE c.order_id=? AND CONCAT('MP-',p.payment_id)=? AND r.externally_confirmed=TRUE
                      AND r.confirmed_at IS NOT NULL AND r.confirmation_source IN ('PAYMENT_STATUS','REFUND_QUERY')
                      AND r.amount=p.amount AND p.amount=? AND r.reason='CANCELLED_ORDER'
                    """, Integer.class, order.id(), order.transactionId(), order.total()) > 0)
            return order.withProvenance(order.provenance().withVerifiedUpdate());
        return order;
    }

    private Long accountId(AccountPrincipal principal) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador autenticado");
        }
        return principal.accountId();
    }
}
