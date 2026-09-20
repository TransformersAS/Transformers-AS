package com.transformersas.marketplace.claims;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Reclamaciones del comprador (CU-13). Solo el rol COMPRADOR llega aquí (ver SecurityConfiguration). */
@RestController
@RequestMapping("/api/claims")
public class BuyerClaimController {

    public record OpenClaimRequest(
            @NotNull Long orderId,
            @NotNull Long productId,
            @NotBlank @Size(max = 1000) String description,
            @Size(max = 5) List<@NotBlank @Size(max = 500) String> evidenceUrls
    ) {
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String message) {
    }

    public record EscalateRequest(@Size(max = 1000) String reason) {
    }

    private final ClaimService service;

    public BuyerClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse open(@Valid @RequestBody OpenClaimRequest request,
                              @AuthenticationPrincipal AccountPrincipal buyer) {
        return service.open(buyer.accountId(), request.orderId(), request.productId(), request.description(),
                request.evidenceUrls());
    }

    @GetMapping
    public List<ClaimResponse> list(@AuthenticationPrincipal AccountPrincipal buyer) {
        return service.listForBuyer(buyer.accountId());
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable Long id, @AuthenticationPrincipal AccountPrincipal buyer) {
        return service.getForBuyer(buyer.accountId(), id);
    }

    @PostMapping("/{id}/messages")
    public ClaimResponse addMessage(@PathVariable Long id, @Valid @RequestBody MessageRequest request,
                                    @AuthenticationPrincipal AccountPrincipal buyer) {
        return service.addBuyerMessage(buyer.accountId(), id, request.message());
    }

    @PostMapping("/{id}/accept")
    public ClaimResponse accept(@PathVariable Long id, @AuthenticationPrincipal AccountPrincipal buyer) {
        return service.accept(buyer.accountId(), id);
    }

    @PostMapping("/{id}/escalate")
    public ClaimResponse escalate(@PathVariable Long id, @Valid @RequestBody(required = false) EscalateRequest request,
                                  @AuthenticationPrincipal AccountPrincipal buyer) {
        return service.escalate(buyer.accountId(), id, request == null ? null : request.reason());
    }
}
