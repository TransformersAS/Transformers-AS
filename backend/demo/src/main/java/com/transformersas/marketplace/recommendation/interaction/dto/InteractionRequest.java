package com.transformersas.marketplace.recommendation.interaction.dto;

import com.transformersas.marketplace.recommendation.interaction.InteractionType;

public record InteractionRequest(

    Long userId,

    Long productId,

    InteractionType interactionType,

    String searchTerm

) {
}