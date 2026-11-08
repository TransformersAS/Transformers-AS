package com.transformersas.marketplace.suspensions.domain.repository;

import com.transformersas.marketplace.suspensions.domain.model.PendingOperations;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecord;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecordSummary;

import java.time.LocalDateTime;
import java.util.List;

/** Lectura de los antecedentes confirmados y de lo que una tienda tiene en curso. Solo lee. */
public interface StoreRecordQuery {

    /** Tiendas con al menos un antecedente confirmado o una suspensión, las más comprometidas primero. */
    List<StoreRecordSummary> storesWithRecords(LocalDateTime recentSince);

    /** Antecedentes confirmados de la tienda, del más reciente al más antiguo. */
    List<StoreRecord> confirmedRecords(Long storeId);

    PendingOperations pendingOperations(Long storeId);
}
