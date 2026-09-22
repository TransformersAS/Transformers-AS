package com.transformersas.marketplace.recommendation.interaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserInteractionRepository
    extends JpaRepository<UserInteraction, Long> {

    List<UserInteraction>
        findByUserIdOrderByCreatedAtDesc(
            Long userId
        );
}
