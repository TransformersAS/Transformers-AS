package com.transformersas.marketplace.restrictions.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.restrictions.application.dto.InformationRequestView;
import com.transformersas.marketplace.restrictions.application.usecase.AnswerAccountInformationUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.GetOwnAccountStandingUseCase;
import com.transformersas.marketplace.restrictions.infrastructure.web.request.RestrictionRequests;
import com.transformersas.marketplace.restrictions.infrastructure.web.response.RestrictionResponses;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que la propia persona ve y responde sobre su cuenta (CU-22, postcondiciones 5 y 8). Cualquier cuenta
 * autenticada entra ({@code /api/**} exige sesión por defecto): no depende del rol, porque una restricción puede
 * afectar cualquiera. La cuenta sale siempre de la sesión, nunca de la ruta.
 */
@RestController
@RequestMapping("/api/account")
public class AccountStandingController {

    private final GetOwnAccountStandingUseCase standing;
    private final AnswerAccountInformationUseCase answer;

    public AccountStandingController(GetOwnAccountStandingUseCase standing, AnswerAccountInformationUseCase answer) {
        this.standing = standing;
        this.answer = answer;
    }

    @GetMapping("/standing")
    public RestrictionResponses.Standing standing(@AuthenticationPrincipal AccountPrincipal account) {
        return RestrictionResponses.Standing.from(standing.execute(account.accountId()));
    }

    @PostMapping("/information-requests/{requestId}/answer")
    public RestrictionResponses.Information answer(@PathVariable Long requestId,
                                                    @RequestBody RestrictionRequests.Answer body,
                                                    @AuthenticationPrincipal AccountPrincipal account) {
        var request = answer.execute(account.accountId(), requestId, body.response());
        return RestrictionResponses.Information.from(new InformationRequestView(request, request.status()));
    }
}
