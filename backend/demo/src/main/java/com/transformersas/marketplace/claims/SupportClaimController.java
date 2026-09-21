package com.transformersas.marketplace.claims;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Reclamaciones escaladas para el agente de soporte (CU-13). Solo el rol SOPORTE llega aquí (/api/support/**). */
@RestController
@RequestMapping("/api/support/claims")
public class SupportClaimController {

    /** decision: REFUND_GRANTED (con amount) o REJECTED (sin amount). note explica la decisión al comprador y al vendedor. */
    public record ResolveRequest(
            @NotNull ClaimResolution decision,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 1000) String note
    ) {
    }

    private final ClaimService service;

    public SupportClaimController(ClaimService service) {
        this.service = service;
    }

    /** Sin filtro muestra la cola de trabajo: las escaladas, la más antigua primero. */
    @GetMapping
    public List<ClaimResponse> list(@RequestParam(defaultValue = "ESCALATED") ClaimStatus status) {
        return service.listByStatus(status);
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/resolve")
    public ClaimResponse resolve(@PathVariable Long id, @Valid @RequestBody ResolveRequest request,
                                 @AuthenticationPrincipal AccountPrincipal agent) {
        return service.resolve(agent.accountId(), id, request.decision(), request.amount(), request.note());
    }
}
