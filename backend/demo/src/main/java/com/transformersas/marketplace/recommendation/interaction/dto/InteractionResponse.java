package com.transformersas.marketplace.recommendation.interaction.dto;

import com.transformersas.marketplace.recommendation.interaction.InteractionType;
import com.transformersas.marketplace.recommendation.interaction.UserInteraction;

import java.time.LocalDateTime;

public record InteractionResponse(

    Long id,

    Long userId,

    Long productId,

    InteractionType interactionType,

    String searchTerm,

    LocalDateTime createdAt

) {

    public static InteractionResponse from(
        UserInteraction interaction
    ) {

        return new InteractionResponse(

            interaction.getId(),

            interaction.getUserId(),

            interaction.getProductId(),

            interaction.getInteractionType(),

            interaction.getSearchTerm(),

            interaction.getCreatedAt()
        );
    }
}