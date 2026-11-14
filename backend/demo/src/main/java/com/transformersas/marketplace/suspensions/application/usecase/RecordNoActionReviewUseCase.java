package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.suspensions.domain.model.StoreReview;
import com.transformersas.marketplace.suspensions.domain.model.Texts;
import com.transformersas.marketplace.suspensions.domain.repository.StoreReviewRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A1: los antecedentes no justifican una suspensión. La revisión queda registrada y auditada, y la tienda sigue
 * operando sin ningún cambio (postcondición 1).
 */
@Service
public class RecordNoActionReviewUseCase {

    private final GetStoreOperationalStatusUseCase stores;
    private final StoreReviewRepository reviews;
    private final SuspensionAudit audit;

    public RecordNoActionReviewUseCase(GetStoreOperationalStatusUseCase stores, StoreReviewRepository reviews,
                                       SuspensionAudit audit) {
        this.stores = stores;
        this.reviews = reviews;
        this.audit = audit;
    }

    @Transactional
    public StoreReview execute(Long adminId, Long storeId, String note) {
        if (stores.execute(storeId).isEmpty()) {
            throw BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe");
        }
        String text = Texts.required(note, 10, 1000, "REVIEW_NOTE_REQUIRED", "note", "La conclusión");
        StoreReview review = new StoreReview(null, storeId, adminId, StoreReview.Decision.NO_ACTION, text, null,
                LocalDateTime.now());
        Long id = reviews.insert(review);
        audit.record(adminId, "STORE_REVIEWED_NO_ACTION", storeId, Map.of("reviewId", id));
        return new StoreReview(id, storeId, adminId, review.decision(), text, null, review.createdAt());
    }
}
