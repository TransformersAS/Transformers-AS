package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.dto.CancelOrderCommand;
import com.transformersas.marketplace.orders.application.dto.CancelOrderResult;
import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.domain.model.CancellationReason;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Autoriza la cancelación directa del comprador y delega todos sus efectos al caso de uso compartido. */
@Service
public class RequestOrderCancellation {
    private final OrderRepository orders;
    private final CancelOrderUseCase cancelOrder;

    public RequestOrderCancellation(OrderRepository orders, CancelOrderUseCase cancelOrder) {
        this.orders = orders;
        this.cancelOrder = cancelOrder;
    }

    // Sin transacción exterior: CancelOrderUseCase confirma la cancelación antes de procesar el reembolso.
    public CancelOrderResult execute(Long id, AccountPrincipal principal, CancellationReason reason, String details) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador autenticado");
        }
        if (principal.activeRole() != Role.COMPRADOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere el rol activo COMPRADOR");
        }
        if (!orders.existsByIdAndAccountId(id, principal.accountId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        if (reason != null && reason != CancellationReason.CHANGED_MIND && reason != CancellationReason.OTHER) {
            throw BusinessException.invalid("CANCELLATION_REASON_INVALID", "Selecciona un motivo de cancelación del comprador");
        }
        return cancelOrder.execute(new CancelOrderCommand(id, null, CancellationInitiator.BUYER,
                principal.accountId(), reason, details));
    }
}
