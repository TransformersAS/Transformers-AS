package com.transformersas.marketplace.logistics.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.logistics.application.dto.ReturnViewer;
import com.transformersas.marketplace.logistics.application.usecase.GetReturnTrackingUseCase;
import com.transformersas.marketplace.logistics.application.usecase.RefreshReturnTrackingUseCase;
import com.transformersas.marketplace.logistics.infrastructure.web.request.EmptyRefreshRequest;
import com.transformersas.marketplace.logistics.infrastructure.web.response.ReturnTrackingResponse;
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
 * Seguimiento logístico de devoluciones (RF-051, RF-110). El comprador consulta las suyas y la tienda las de su
 * tienda; nadie modifica estados (A7): «actualizar» solo vuelve a consultar al servicio logístico. Adaptador HTTP
 * delgado: la identidad sale de la sesión o de CurrentActorProvider y cada endpoint delega en un caso de uso.
 */
@RestController
@RequestMapping("/api")
public class ReturnTrackingController {

    private final GetReturnTrackingUseCase getTracking;
    private final RefreshReturnTrackingUseCase refreshTracking;
    private final CurrentActorProvider actor;

    public ReturnTrackingController(GetReturnTrackingUseCase getTracking, RefreshReturnTrackingUseCase refreshTracking,
                                    CurrentActorProvider actor) {
        this.getTracking = getTracking;
        this.refreshTracking = refreshTracking;
        this.actor = actor;
    }

    @GetMapping("/returns/{returnId}/tracking")
    public ReturnTrackingResponse buyerTracking(@PathVariable Long returnId,
                                                @AuthenticationPrincipal AccountPrincipal principal) {
        return ReturnTrackingResponse.from(getTracking.execute(returnId, buyer(principal)));
    }

    @PostMapping("/returns/{returnId}/tracking/refresh")
    public ReturnTrackingResponse buyerRefresh(@PathVariable Long returnId,
                                               @AuthenticationPrincipal AccountPrincipal principal,
                                               @RequestBody(required = false) EmptyRefreshRequest body) {
        return ReturnTrackingResponse.from(refreshTracking.execute(returnId, buyer(principal)));
    }

    @GetMapping("/seller/returns/{returnId}/tracking")
    public ReturnTrackingResponse sellerTracking(@PathVariable Long returnId) {
        return ReturnTrackingResponse.from(getTracking.execute(returnId, ReturnViewer.seller(actor.storeId())));
    }

    @PostMapping("/seller/returns/{returnId}/tracking/refresh")
    public ReturnTrackingResponse sellerRefresh(@PathVariable Long returnId,
                                                @RequestBody(required = false) EmptyRefreshRequest body) {
        return ReturnTrackingResponse.from(refreshTracking.execute(returnId, ReturnViewer.seller(actor.storeId())));
    }

    private static ReturnViewer buyer(AccountPrincipal principal) {
        if (principal == null || principal.accountId() == null || principal.accountId() <= 0) {
            throw BusinessException.unauthenticated("UNAUTHENTICATED", "Se requiere un comprador autenticado");
        }
        return ReturnViewer.buyer(principal.accountId());
    }
}
