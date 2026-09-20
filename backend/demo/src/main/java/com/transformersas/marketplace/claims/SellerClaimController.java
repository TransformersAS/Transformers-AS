package com.transformersas.marketplace.claims;

import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reclamaciones de la tienda del vendedor (CU-13). La tienda sale de la sesión con CurrentActorProvider, que además
 * exige el rol activo VENDEDOR.
 */
@RestController
@RequestMapping("/api/seller/claims")
public class SellerClaimController {

    public record InfoRequest(@NotBlank @Size(max = 1000) String message) {
    }

    /** refundAmount es opcional: sin monto (o en 0) la solución no incluye reembolso. */
    public record ProposalRequest(
            @NotBlank @Size(max = 1000) String message,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal refundAmount
    ) {
    }

    private final CurrentActorProvider actor;
    private final ClaimService service;

    public SellerClaimController(CurrentActorProvider actor, ClaimService service) {
        this.actor = actor;
        this.service = service;
    }

    @GetMapping
    public List<ClaimResponse> list() {
        return service.listForStore(actor.storeId());
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable Long id) {
        return service.getForStore(actor.storeId(), id);
    }

    @PostMapping("/{id}/request-info")
    public ClaimResponse requestInfo(@PathVariable Long id, @Valid @RequestBody InfoRequest request) {
        return service.requestInfo(actor.storeId(), actor.actorId(), id, request.message());
    }

    @PostMapping("/{id}/propose")
    public ClaimResponse propose(@PathVariable Long id, @Valid @RequestBody ProposalRequest request) {
        return service.propose(actor.storeId(), actor.actorId(), id, request.message(), request.refundAmount());
    }
}
