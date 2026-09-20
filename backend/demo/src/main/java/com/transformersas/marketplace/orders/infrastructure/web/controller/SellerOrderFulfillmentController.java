package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.orders.application.dto.IssueCommands;
import com.transformersas.marketplace.orders.application.dto.OrderActionCommand;
import com.transformersas.marketplace.orders.application.dto.ShipmentOutcome;
import com.transformersas.marketplace.orders.application.usecase.MarkReadyForDispatchUseCase;
import com.transformersas.marketplace.orders.application.usecase.RegisterOrderIssueUseCase;
import com.transformersas.marketplace.orders.application.usecase.RequestShipmentUseCase;
import com.transformersas.marketplace.orders.application.usecase.ResolveOrderIssueUseCase;
import com.transformersas.marketplace.orders.infrastructure.web.request.EmptyActionRequest;
import com.transformersas.marketplace.orders.infrastructure.web.request.RegisterIssueRequest;
import com.transformersas.marketplace.orders.infrastructure.web.response.IssueResponse;
import com.transformersas.marketplace.orders.infrastructure.web.response.ReadyForDispatchResponse;
import com.transformersas.marketplace.orders.infrastructure.web.response.ShipmentResponse;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Novedades, listo para despacho y envío de los pedidos de la tienda (CU-23). Adaptador HTTP delgado: la tienda y el
 * actor salen de CurrentActorProvider y cada endpoint delega en un único caso de uso.
 */
@RestController
@RequestMapping("/api/seller/orders/{orderId}")
public class SellerOrderFulfillmentController {

    private final CurrentActorProvider actor;
    private final RegisterOrderIssueUseCase registerIssue;
    private final ResolveOrderIssueUseCase resolveIssue;
    private final MarkReadyForDispatchUseCase markReady;
    private final RequestShipmentUseCase requestShipment;

    public SellerOrderFulfillmentController(CurrentActorProvider actor, RegisterOrderIssueUseCase registerIssue,
                                            ResolveOrderIssueUseCase resolveIssue,
                                            MarkReadyForDispatchUseCase markReady,
                                            RequestShipmentUseCase requestShipment) {
        this.actor = actor;
        this.registerIssue = registerIssue;
        this.resolveIssue = resolveIssue;
        this.markReady = markReady;
        this.requestShipment = requestShipment;
    }

    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse registerIssue(@PathVariable Long orderId, @Valid @RequestBody RegisterIssueRequest request) {
        return IssueResponse.from(registerIssue.execute(new IssueCommands.Register(orderId, actor.storeId(),
                actor.actorId(), request.type(), request.description())));
    }

    @PostMapping("/issues/{issueId}/resolve")
    public IssueResponse resolveIssue(@PathVariable Long orderId, @PathVariable Long issueId,
                                      @RequestBody(required = false) EmptyActionRequest body) {
        return IssueResponse.from(resolveIssue.execute(
                new IssueCommands.Resolve(orderId, actor.storeId(), actor.actorId(), issueId)));
    }

    @PostMapping("/ready-for-dispatch")
    public ReadyForDispatchResponse readyForDispatch(@PathVariable Long orderId,
                                                     @RequestBody(required = false) EmptyActionRequest body) {
        return ReadyForDispatchResponse.from(
                markReady.execute(new OrderActionCommand(orderId, actor.storeId(), actor.actorId())));
    }

    /** Reintento manual del envío: 201 si se creó ahora, 200 si ya existía y se reutilizó su referencia (A6). */
    @PostMapping("/shipment")
    public ResponseEntity<ShipmentResponse> shipment(@PathVariable Long orderId,
                                                     @RequestBody(required = false) EmptyActionRequest body) {
        ShipmentOutcome outcome = requestShipment.execute(
                new OrderActionCommand(orderId, actor.storeId(), actor.actorId()));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ShipmentResponse.from(outcome));
    }
}
