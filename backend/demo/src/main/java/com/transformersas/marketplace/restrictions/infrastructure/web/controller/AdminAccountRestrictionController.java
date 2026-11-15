package com.transformersas.marketplace.restrictions.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.application.dto.InformationRequestView;
import com.transformersas.marketplace.restrictions.application.dto.RestrictionView;
import com.transformersas.marketplace.restrictions.application.usecase.ApplyRestrictionUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.GetAccountEvaluationUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.ModifyRestrictionUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.RequestAccountInformationUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.SearchAccountsUseCase;
import com.transformersas.marketplace.restrictions.application.usecase.WithdrawRestrictionUseCase;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.infrastructure.web.request.RestrictionRequests;
import com.transformersas.marketplace.restrictions.infrastructure.web.response.RestrictionResponses;
import com.transformersas.marketplace.users.domain.model.Role;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gestión de cuentas y restricciones de acceso por el administrador (CU-22). Todo /api/admin/** exige el rol activo
 * ADMIN (SecurityConfiguration); el administrador responsable sale de la sesión, nunca del cuerpo. Cada endpoint
 * delega en un único caso de uso.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAccountRestrictionController {

    private final SearchAccountsUseCase search;
    private final GetAccountEvaluationUseCase evaluation;
    private final RequestAccountInformationUseCase requestInformation;
    private final ApplyRestrictionUseCase apply;
    private final ModifyRestrictionUseCase modify;
    private final WithdrawRestrictionUseCase withdraw;

    public AdminAccountRestrictionController(SearchAccountsUseCase search, GetAccountEvaluationUseCase evaluation,
                                             RequestAccountInformationUseCase requestInformation,
                                             ApplyRestrictionUseCase apply, ModifyRestrictionUseCase modify,
                                             WithdrawRestrictionUseCase withdraw) {
        this.search = search;
        this.evaluation = evaluation;
        this.requestInformation = requestInformation;
        this.apply = apply;
        this.modify = modify;
        this.withdraw = withdraw;
    }

    @GetMapping("/accounts")
    public RestrictionResponses.Account search(@RequestParam String email) {
        return search.execute(email).map(RestrictionResponses.Account::from)
                .orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "La cuenta no existe"));
    }

    @GetMapping("/accounts/{accountId}/evaluation")
    public RestrictionResponses.Evaluation evaluate(@PathVariable Long accountId) {
        return RestrictionResponses.Evaluation.from(evaluation.execute(accountId));
    }

    @PostMapping("/accounts/{accountId}/information-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponses.Information requestInformation(@PathVariable Long accountId,
                                                                @RequestBody RestrictionRequests.Information body,
                                                                @AuthenticationPrincipal AccountPrincipal admin) {
        var request = requestInformation.execute(admin.accountId(), accountId, body.message());
        return RestrictionResponses.Information.from(new InformationRequestView(request, request.status()));
    }

    @PostMapping("/accounts/{accountId}/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponses.Restriction restrict(@PathVariable Long accountId,
                                                      @RequestBody RestrictionRequests.Restriction body,
                                                      @AuthenticationPrincipal AccountPrincipal admin) {
        var restriction = apply.execute(admin.accountId(), accountId, scope(body.scope()), role(body.role()),
                body.reason(), body.permanent(), body.durationDays());
        return RestrictionResponses.Restriction.from(new RestrictionView(restriction, List.of()), true);
    }

    @PutMapping("/restrictions/{restrictionId}/duration")
    public RestrictionResponses.Restriction changeDuration(@PathVariable Long restrictionId,
                                                            @RequestBody RestrictionRequests.DurationChange body,
                                                            @AuthenticationPrincipal AccountPrincipal admin) {
        var restriction = modify.changeDuration(admin.accountId(), restrictionId, body.durationDays(),
                body.justification());
        return RestrictionResponses.Restriction.from(new RestrictionView(restriction, List.of()), true);
    }

    @PostMapping("/restrictions/{restrictionId}/permanent")
    public RestrictionResponses.Restriction makePermanent(@PathVariable Long restrictionId,
                                                           @RequestBody RestrictionRequests.PermanenceChange body,
                                                           @AuthenticationPrincipal AccountPrincipal admin) {
        var restriction = modify.makePermanent(admin.accountId(), restrictionId, body.justification());
        return RestrictionResponses.Restriction.from(new RestrictionView(restriction, List.of()), true);
    }

    @PostMapping("/restrictions/{restrictionId}/temporary")
    public RestrictionResponses.Restriction makeTemporary(@PathVariable Long restrictionId,
                                                           @RequestBody RestrictionRequests.PermanenceChange body,
                                                           @AuthenticationPrincipal AccountPrincipal admin) {
        var restriction = modify.makeTemporary(admin.accountId(), restrictionId, body.durationDays(),
                body.justification());
        return RestrictionResponses.Restriction.from(new RestrictionView(restriction, List.of()), true);
    }

    @PostMapping("/restrictions/{restrictionId}/withdraw")
    public RestrictionResponses.Outcome withdraw(@PathVariable Long restrictionId,
                                                 @RequestBody RestrictionRequests.Withdraw body,
                                                 @AuthenticationPrincipal AccountPrincipal admin) {
        return RestrictionResponses.Outcome.from(withdraw.execute(admin.accountId(), restrictionId, body.reason()));
    }

    private static RestrictionScope scope(String value) {
        try {
            return RestrictionScope.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw BusinessException.invalid("RESTRICTION_SCOPE_INVALID", "El alcance debe ser ACCOUNT o ROLE");
        }
    }

    private static Role role(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw BusinessException.invalid("RESTRICTION_ROLE_INVALID", "El rol indicado no existe");
        }
    }
}
