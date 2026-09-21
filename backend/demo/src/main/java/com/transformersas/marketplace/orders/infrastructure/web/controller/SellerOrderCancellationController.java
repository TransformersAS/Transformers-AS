package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.orders.application.dto.CancelOrderCommand;
import com.transformersas.marketplace.orders.application.usecase.CancelOrderUseCase;
import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.infrastructure.web.request.CancelOrderRequest;
import com.transformersas.marketplace.orders.infrastructure.web.response.CancelOrderResponse;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Imposibilidad del vendedor de cumplir el pedido (RF-122): cancelación completa antes del despacho. Adaptador HTTP
 * delgado sobre el caso de uso genérico de cancelación (CU-11 lo reutilizará con el comprador como iniciador).
 */
@RestController
@RequestMapping("/api/seller/orders/{orderId}")
public class SellerOrderCancellationController {

    private final CurrentActorProvider actor;
    private final CancelOrderUseCase cancelOrder;

    public SellerOrderCancellationController(CurrentActorProvider actor, CancelOrderUseCase cancelOrder) {
        this.actor = actor;
        this.cancelOrder = cancelOrder;
    }

    @PostMapping("/cancel")
    public CancelOrderResponse cancel(@PathVariable Long orderId, @Valid @RequestBody CancelOrderRequest request) {
        return CancelOrderResponse.from(cancelOrder.execute(new CancelOrderCommand(orderId, actor.storeId(),
                CancellationInitiator.SELLER, actor.actorId(), request.reasonCode(), request.details())));
    }
}
