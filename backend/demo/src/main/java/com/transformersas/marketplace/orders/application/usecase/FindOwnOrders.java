package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
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

    public Order detail(Long id, AccountPrincipal principal) {
        return orders.findByIdAndAccountId(id, accountId(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
    }

    private Long accountId(AccountPrincipal principal) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador autenticado");
        }
        return principal.accountId();
    }
}
