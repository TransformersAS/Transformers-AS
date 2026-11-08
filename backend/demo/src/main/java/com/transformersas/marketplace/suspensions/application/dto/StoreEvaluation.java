package com.transformersas.marketplace.suspensions.application.dto;

import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.suspensions.domain.model.PendingOperations;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecord;
import com.transformersas.marketplace.suspensions.domain.model.StoreReview;

import java.util.List;

/**
 * Todo lo que el administrador revisa de una tienda (pasos 4 a 9): sus antecedentes confirmados, las suspensiones
 * anteriores con su historia, las revisiones, las aclaraciones y lo que tiene en curso. recentRecords y recidivist
 * ayudan a evaluar la reincidencia; activeSuspension es nula si no hay una vigente.
 */
public record StoreEvaluation(StoreOperationalStatus store, PendingOperations pending, List<StoreRecord> records,
                             long recentRecords, boolean recidivist, SuspensionView activeSuspension,
                             List<SuspensionView> suspensions, List<StoreReview> reviews,
                             List<ClarificationView> clarifications) {
}
