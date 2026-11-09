package com.transformersas.marketplace.suspensions.domain.repository;

import com.transformersas.marketplace.suspensions.domain.model.StoreReview;

import java.util.List;

public interface StoreReviewRepository {

    Long insert(StoreReview review);

    /** Revisiones de la tienda, de la más reciente a la más antigua. */
    List<StoreReview> findByStore(Long storeId);
}
