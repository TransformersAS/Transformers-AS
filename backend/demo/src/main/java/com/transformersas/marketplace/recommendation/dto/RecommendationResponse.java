package com.transformersas.marketplace.recommendation.dto;

import java.util.List;

public record RecommendationResponse(

    Long userId,

    String strategy,

    List<RecommendedProductResponse> products

) {
}