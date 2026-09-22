package com.transformersas.marketplace.recommendation.interaction;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.transformersas.marketplace.recommendation.interaction.dto.InteractionRequest;
import com.transformersas.marketplace.recommendation.interaction.dto.InteractionResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/interactions")
@CrossOrigin(origins = "http://localhost:4300")
public class InteractionController {

    private final InteractionService service;


    public InteractionController(
        InteractionService service
    ) {

        this.service = service;
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InteractionResponse register(
        @RequestBody
        InteractionRequest request,
        @AuthenticationPrincipal
        AccountPrincipal principal
    ) {

        return service.register(
            new InteractionRequest(principal.accountId(), request.productId(), request.interactionType(), request.searchTerm())
        );
    }


    @GetMapping
    public List<InteractionResponse>
        findByUser(
            @AuthenticationPrincipal
            AccountPrincipal principal
        ) {

        return service.findByUser(
            principal.accountId()
        );
    }
}