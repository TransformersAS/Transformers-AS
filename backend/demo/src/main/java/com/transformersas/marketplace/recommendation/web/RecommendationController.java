package com.transformersas.marketplace.recommendation.web;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.transformersas.marketplace.recommendation.application.RecommendationService;
import com.transformersas.marketplace.recommendation.dto.RecommendationResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    "/api/recommendations"
)
@CrossOrigin(
    origins = "http://localhost:4300"
)
public class RecommendationController {

    private final RecommendationService
        service;


    public RecommendationController(
        RecommendationService service
    ) {

        this.service = service;
    }


    @GetMapping
    public RecommendationResponse recommend(

        @AuthenticationPrincipal
        AccountPrincipal principal

    ) {

        return service.recommend(
            principal.accountId()
        );
    }
}