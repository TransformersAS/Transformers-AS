package com.transformersas.marketplace.suspensions.infrastructure.web.controller;

import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.suspensions.application.dto.ClarificationView;
import com.transformersas.marketplace.suspensions.application.usecase.AnswerClarificationUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.GetOwnStoreStandingUseCase;
import com.transformersas.marketplace.suspensions.infrastructure.web.request.SuspensionRequests;
import com.transformersas.marketplace.suspensions.infrastructure.web.response.SuspensionResponses;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El vendedor consulta el estado de su tienda y responde las aclaraciones que le pide la administración (CU-27). La
 * tienda sale de la sesión (CurrentActorProvider exige el rol activo VENDEDOR): ve y responde solo lo suyo. Estas
 * rutas siguen disponibles durante una suspensión.
 */
@RestController
@RequestMapping("/api/seller/store/standing")
public class SellerStoreStandingController {

    private final CurrentActorProvider actor;
    private final GetOwnStoreStandingUseCase standing;
    private final AnswerClarificationUseCase answer;

    public SellerStoreStandingController(CurrentActorProvider actor, GetOwnStoreStandingUseCase standing,
                                         AnswerClarificationUseCase answer) {
        this.actor = actor;
        this.standing = standing;
        this.answer = answer;
    }

    @GetMapping
    public SuspensionResponses.Standing get() {
        return SuspensionResponses.Standing.from(standing.execute(actor.storeId()));
    }

    @PostMapping("/clarifications/{clarificationId}/response")
    public SuspensionResponses.Clarification answer(@PathVariable Long clarificationId,
                                                    @RequestBody SuspensionRequests.Answer body) {
        var request = answer.execute(actor.actorId(), actor.storeId(), clarificationId, body.response());
        return SuspensionResponses.Clarification.from(new ClarificationView(request, request.status()));
    }
}
