package com.transformersas.marketplace.recommendation.interaction;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_interactions")
public class UserInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "interaction_type",
        nullable = false
    )
    private InteractionType interactionType;

    @Column(name = "search_term")
    private String searchTerm;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;


    public UserInteraction() {
    }


    public UserInteraction(
        Long userId,
        Long productId,
        InteractionType interactionType,
        String searchTerm,
        LocalDateTime createdAt
    ) {

        this.userId = userId;
        this.productId = productId;
        this.interactionType = interactionType;
        this.searchTerm = searchTerm;
        this.createdAt = createdAt;
    }


    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public InteractionType getInteractionType() {
        return interactionType;
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
