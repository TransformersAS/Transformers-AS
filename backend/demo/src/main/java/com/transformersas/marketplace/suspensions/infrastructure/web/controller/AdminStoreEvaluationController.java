package com.transformersas.marketplace.suspensions.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionView;
import com.transformersas.marketplace.suspensions.application.usecase.ApplySuspensionUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.ChangeSuspensionDurationUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.GetStoreEvaluationUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.LiftSuspensionUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.ListStoresWithRecordsUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.PreviewSuspensionUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.RecordNoActionReviewUseCase;
import com.transformersas.marketplace.suspensions.application.usecase.RequestClarificationUseCase;
import com.transformersas.marketplace.suspensions.application.dto.ClarificationView;
import com.transformersas.marketplace.suspensions.infrastructure.web.request.SuspensionRequests;
import com.transformersas.marketplace.suspensions.infrastructure.web.response.SuspensionResponses;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Evaluación y suspensión de tiendas por el administrador (CU-27). Todo /api/admin/** exige el rol activo ADMIN
 * (SecurityConfiguration); el administrador responsable sale de la sesión, nunca del cuerpo. Cada endpoint delega en
 * un único caso de uso.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminStoreEvaluationController {

    private final ListStoresWithRecordsUseCase list;
    private final GetStoreEvaluationUseCase evaluation;
    private final RequestClarificationUseCase clarification;
    private final RecordNoActionReviewUseCase noAction;
    private final PreviewSuspensionUseCase preview;
    private final ApplySuspensionUseCase apply;
    private final ChangeSuspensionDurationUseCase changeDuration;
    private final LiftSuspensionUseCase lift;

    public AdminStoreEvaluationController(ListStoresWithRecordsUseCase list, GetStoreEvaluationUseCase evaluation,
                                          RequestClarificationUseCase clarification,
                                          RecordNoActionReviewUseCase noAction, PreviewSuspensionUseCase preview,
                                          ApplySuspensionUseCase apply, ChangeSuspensionDurationUseCase changeDuration,
                                          LiftSuspensionUseCase lift) {
        this.list = list;
        this.evaluation = evaluation;
        this.clarification = clarification;
        this.noAction = noAction;
        this.preview = preview;
        this.apply = apply;
        this.changeDuration = changeDuration;
        this.lift = lift;
    }

    @GetMapping("/store-evaluations")
    public List<SuspensionResponses.StoreSummary> list() {
        return list.execute().stream().map(SuspensionResponses.StoreSummary::from).toList();
    }

    @GetMapping("/store-evaluations/{storeId}")
    public SuspensionResponses.Evaluation get(@PathVariable Long storeId) {
        return SuspensionResponses.Evaluation.from(evaluation.execute(storeId));
    }

    @PostMapping("/store-evaluations/{storeId}/clarifications")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionResponses.Clarification requestClarification(@PathVariable Long storeId,
                                                                  @RequestBody SuspensionRequests.Clarification body,
                                                                  @AuthenticationPrincipal AccountPrincipal admin) {
        var request = clarification.execute(admin.accountId(), storeId, body.message());
        return SuspensionResponses.Clarification.from(new ClarificationView(request, request.status()));
    }

    @PostMapping("/store-evaluations/{storeId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionResponses.Review recordNoAction(@PathVariable Long storeId,
                                                     @RequestBody SuspensionRequests.NoActionReview body,
                                                     @AuthenticationPrincipal AccountPrincipal admin) {
        return SuspensionResponses.Review.from(noAction.execute(admin.accountId(), storeId, body.note()));
    }

    @PostMapping("/store-evaluations/{storeId}/suspensions/preview")
    public SuspensionResponses.Preview preview(@PathVariable Long storeId,
                                               @RequestBody SuspensionRequests.Suspension body) {
        return SuspensionResponses.Preview.from(preview.execute(storeId, body.reason(), body.durationDays()));
    }

    @PostMapping("/store-evaluations/{storeId}/suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuspensionResponses.Suspension suspend(@PathVariable Long storeId,
                                                  @RequestBody SuspensionRequests.Suspension body,
                                                  @AuthenticationPrincipal AccountPrincipal admin) {
        var suspension = apply.execute(admin.accountId(), storeId, body.reason(), body.durationDays());
        return SuspensionResponses.Suspension.from(new SuspensionView(suspension, List.of()), true);
    }

    @PutMapping("/store-suspensions/{suspensionId}/duration")
    public SuspensionResponses.Suspension changeDuration(@PathVariable Long suspensionId,
                                                         @RequestBody SuspensionRequests.DurationChange body,
                                                         @AuthenticationPrincipal AccountPrincipal admin) {
        var suspension = changeDuration.execute(admin.accountId(), suspensionId, body.durationDays(),
                body.justification());
        return SuspensionResponses.Suspension.from(new SuspensionView(suspension, List.of()), true);
    }

    @PostMapping("/store-suspensions/{suspensionId}/lift")
    public SuspensionResponses.Outcome lift(@PathVariable Long suspensionId,
                                            @RequestBody SuspensionRequests.Lift body,
                                            @AuthenticationPrincipal AccountPrincipal admin) {
        return SuspensionResponses.Outcome.from(lift.execute(admin.accountId(), suspensionId, body.reason()));
    }
}
