package com.transformersas.marketplace.recommendation.web;

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

        @RequestParam
        Long userId

    ) {

        return service.recommend(
            userId
        );
    }
}