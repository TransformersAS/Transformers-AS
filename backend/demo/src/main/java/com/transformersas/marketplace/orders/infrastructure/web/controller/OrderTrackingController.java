package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.usecase.GetOrderTrackingUseCase;
import com.transformersas.marketplace.orders.application.usecase.RefreshOrderTrackingUseCase;
import com.transformersas.marketplace.orders.infrastructure.web.request.EmptyActionRequest;
import com.transformersas.marketplace.orders.infrastructure.web.response.OrderTrackingResponse;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seguimiento logístico de pedidos (CU-24, RF-117). El comprador consulta los suyos y la tienda los de su tienda;
 * ninguno modifica estados (A8): «actualizar» solo vuelve a consultar al servicio logístico. Adaptador HTTP delgado:
 * la identidad sale de la sesión o de CurrentActorProvider y cada endpoint delega en un único caso de uso.
 */
@RestController
@RequestMapping("/api")
public class OrderTrackingController {

    private final GetOrderTrackingUseCase getTracking;
    private final RefreshOrderTrackingUseCase refreshTracking;
    private final CurrentActorProvider actor;

    public OrderTrackingController(GetOrderTrackingUseCase getTracking, RefreshOrderTrackingUseCase refreshTracking,
                                   CurrentActorProvider actor) {
        this.getTracking = getTracking;
        this.refreshTracking = refreshTracking;
        this.actor = actor;
    }

    @GetMapping("/orders/{orderId}/tracking")
    public OrderTrackingResponse buyerTracking(@PathVariable Long orderId,
                                               @AuthenticationPrincipal AccountPrincipal principal) {
        return OrderTrackingResponse.from(getTracking.forBuyer(orderId, accountId(principal)));
    }

    @PostMapping("/orders/{orderId}/tracking/refresh")
    public OrderTrackingResponse buyerRefresh(@PathVariable Long orderId,
                                              @AuthenticationPrincipal AccountPrincipal principal,
                                              @RequestBody(required = false) EmptyActionRequest body) {
        return OrderTrackingResponse.from(refreshTracking.forBuyer(orderId, accountId(principal)));
    }

    @GetMapping("/seller/orders/{orderId}/tracking")
    public OrderTrackingResponse sellerTracking(@PathVariable Long orderId) {
        return OrderTrackingResponse.from(getTracking.forSeller(orderId, actor.storeId()));
    }

    @PostMapping("/seller/orders/{orderId}/tracking/refresh")
    public OrderTrackingResponse sellerRefresh(@PathVariable Long orderId,
                                               @RequestBody(required = false) EmptyActionRequest body) {
        return OrderTrackingResponse.from(refreshTracking.forSeller(orderId, actor.storeId()));
    }

    private static Long accountId(AccountPrincipal principal) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw BusinessException.unauthenticated("UNAUTHENTICATED", "Se requiere un comprador autenticado");
        }
        return principal.accountId();
    }
}
