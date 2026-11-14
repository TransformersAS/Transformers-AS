package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.suspensions.application.dto.StoreEvaluation;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionView;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecord;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecordSummary;
import com.transformersas.marketplace.suspensions.domain.repository.StoreRecordQuery;
import com.transformersas.marketplace.suspensions.domain.repository.StoreReviewRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Antecedentes y situación de una tienda para evaluarla (pasos 3 a 5 y 9, RF-169). Solo muestra lo necesario para
 * decidir: nada de datos personales de compradores.
 */
@Service
public class GetStoreEvaluationUseCase {

    private final GetStoreOperationalStatusUseCase stores;
    private final StoreRecordQuery records;
    private final StoreReviewRepository reviews;
    private final SuspensionHistory history;

    public GetStoreEvaluationUseCase(GetStoreOperationalStatusUseCase stores, StoreRecordQuery records,
                                     StoreReviewRepository reviews, SuspensionHistory history) {
        this.stores = stores;
        this.records = records;
        this.reviews = reviews;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public StoreEvaluation execute(Long storeId) {
        StoreOperationalStatus store = stores.execute(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        LocalDateTime now = LocalDateTime.now();
        List<StoreRecord> confirmed = records.confirmedRecords(storeId);
        List<SuspensionView> suspensions = history.suspensionsOf(storeId);
        long recent = recentRecords(confirmed, now.minusDays(StoreRecordSummary.RECENT_DAYS));
        return new StoreEvaluation(store, records.pendingOperations(storeId), confirmed, recent,
                StoreRecordSummary.recidivist(recent, suspensions.size()), SuspensionHistory.active(suspensions),
                suspensions, reviews.findByStore(storeId), history.clarificationsOf(storeId, now));
    }

    /** Un caso de moderación con varias decisiones cuenta una sola vez, igual que en la lista. */
    static long recentRecords(List<StoreRecord> confirmed, LocalDateTime since) {
        return confirmed.stream()
                .filter(record -> !record.decidedAt().isBefore(since))
                .map(record -> record.type() + "-" + record.referenceId())
                .distinct()
                .count();
    }
}
