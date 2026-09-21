package com.transformersas.marketplace.returns.infrastructure.web.controller;

import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.application.usecase.ReturnDecisionUseCase;
import com.transformersas.marketplace.returns.application.usecase.ReturnQueryService;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.infrastructure.web.request.ReturnRequests;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;

/**
 * Devoluciones de la tienda (CU-19): lista y revisa solo las de su tienda, pide información, aprueba o rechaza con
 * justificación. La tienda y el rol VENDEDOR salen de {@link CurrentActorProvider}, que también valida que la cuenta sea
 * dueña de la tienda; nunca llegan por ruta ni por cuerpo. Una devolución de otra tienda responde 404.
 */
@RestController
@RequestMapping("/api/seller/return-requests")
public class SellerReturnController {
    private final CurrentActorProvider actor;
    private final ReturnQueryService queries;
    private final ReturnDecisionUseCase decisions;

    public SellerReturnController(CurrentActorProvider actor, ReturnQueryService queries,
                                  ReturnDecisionUseCase decisions) {
        this.actor = actor;
        this.queries = queries;
        this.decisions = decisions;
    }

    /** Las de la tienda, de la más antigua a la más reciente; {@code status} filtra por estado. */
    @GetMapping
    public List<ReturnViews.Summary> list(@RequestParam(required = false) String status) {
        Long storeId = actor.storeId();
        return queries.sellerReturns(storeId, parseStatus(status));
    }

    @GetMapping("/{id}")
    public ReturnViews.Detail detail(@PathVariable Long id) {
        return queries.sellerReturn(actor.storeId(), id);
    }

    @PostMapping("/{id}/review")
    public ReturnViews.Detail review(@PathVariable Long id) {
        Long storeId = actor.storeId();
        decisions.startReview(storeId, actor.actorId(), id);
        return queries.sellerReturn(storeId, id);
    }

    @PostMapping("/{id}/information-requests")
    public ResponseEntity<ReturnViews.Detail> requestInformation(@PathVariable Long id,
                                                                 @RequestBody ReturnRequests.InformationRequest body) {
        Long storeId = actor.storeId();
        decisions.requestInformation(storeId, actor.actorId(), id, body.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(queries.sellerReturn(storeId, id));
    }

    @PostMapping("/{id}/approve")
    public ReturnViews.Detail approve(@PathVariable Long id, @RequestBody(required = false) ReturnRequests.Decision body) {
        Long storeId = actor.storeId();
        decisions.approve(storeId, actor.actorId(), id, body == null ? null : body.note());
        return queries.sellerReturn(storeId, id);
    }

    @PostMapping("/{id}/reject")
    public ReturnViews.Detail reject(@PathVariable Long id, @RequestBody(required = false) ReturnRequests.Decision body) {
        Long storeId = actor.storeId();
        decisions.reject(storeId, actor.actorId(), id, body == null ? null : body.note());
        return queries.sellerReturn(storeId, id);
    }

    /** Reporta un problema con lo recibido dentro de las 24 h de inspección; abre la reclamación del comprador. */
    @PostMapping("/{id}/report-problem")
    public ReturnViews.Detail reportProblem(@PathVariable Long id, @RequestBody ReturnRequests.Problem body) {
        Long storeId = actor.storeId();
        decisions.reportProblem(storeId, actor.actorId(), id, body.description());
        return queries.sellerReturn(storeId, id);
    }

    @GetMapping("/{id}/evidences/{ordinal}")
    public ResponseEntity<byte[]> evidence(WebRequest webRequest, @PathVariable Long id, @PathVariable int ordinal) {
        return ReturnEvidenceResponses.serve(webRequest, queries.sellerEvidence(actor.storeId(), id, ordinal));
    }

    private static ReturnStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReturnStatus.valueOf(status.strip());
        } catch (IllegalArgumentException unknown) {
            throw ReturnException.field("status", "RETURN_STATUS_INVALID", "El estado no es válido");
        }
    }
}
