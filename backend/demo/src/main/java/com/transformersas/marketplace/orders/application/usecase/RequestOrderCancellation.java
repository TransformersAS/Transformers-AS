package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RequestOrderCancellation {
    private final OrderRepository orders;

    public RequestOrderCancellation(OrderRepository orders) { this.orders = orders; }

    @Transactional
    public void execute(Long id, AccountPrincipal principal) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador autenticado");
        }
        Long accountId = principal.accountId();
        if (orders.requestCancellation(id, accountId)) return;
        if (!orders.existsByIdAndAccountId(id, accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "El estado del pedido no permite solicitar cancelación");
    }
}
