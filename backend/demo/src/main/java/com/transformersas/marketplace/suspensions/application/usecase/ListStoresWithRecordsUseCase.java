package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.suspensions.domain.model.StoreRecordSummary;
import com.transformersas.marketplace.suspensions.domain.repository.StoreRecordQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Tiendas con antecedentes confirmados relevantes (paso 2, RF-168). */
@Service
public class ListStoresWithRecordsUseCase {

    private final StoreRecordQuery records;

    public ListStoresWithRecordsUseCase(StoreRecordQuery records) {
        this.records = records;
    }

    @Transactional(readOnly = true)
    public List<StoreRecordSummary> execute() {
        return records.storesWithRecords(LocalDateTime.now().minusDays(StoreRecordSummary.RECENT_DAYS));
    }
}
