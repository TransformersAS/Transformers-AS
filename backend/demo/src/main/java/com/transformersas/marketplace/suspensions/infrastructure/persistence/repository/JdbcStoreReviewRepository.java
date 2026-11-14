package com.transformersas.marketplace.suspensions.infrastructure.persistence.repository;

import com.transformersas.marketplace.suspensions.domain.model.StoreReview;
import com.transformersas.marketplace.suspensions.domain.repository.StoreReviewRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcStoreReviewRepository implements StoreReviewRepository {

    private final JdbcClient jdbc;

    public JdbcStoreReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insert(StoreReview review) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO store_reviews (store_id, reviewed_by_account_id, decision, note, suspension_id,
                                                   created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(review.storeId(), review.reviewedBy(), review.decision().name(), review.note(),
                        review.suspensionId(), review.createdAt())
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public List<StoreReview> findByStore(Long storeId) {
        return jdbc.sql("""
                        SELECT id, store_id, reviewed_by_account_id, decision, note, suspension_id, created_at
                        FROM store_reviews WHERE store_id = ? ORDER BY id DESC""")
                .param(storeId)
                .query((rs, row) -> new StoreReview(rs.getLong("id"), rs.getLong("store_id"),
                        rs.getLong("reviewed_by_account_id"), StoreReview.Decision.valueOf(rs.getString("decision")),
                        rs.getString("note"), rs.getObject("suspension_id", Long.class),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }
}
