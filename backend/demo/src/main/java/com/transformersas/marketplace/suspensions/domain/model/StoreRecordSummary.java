package com.transformersas.marketplace.suspensions.domain.model;

import java.time.LocalDateTime;

/**
 * Una tienda con antecedentes confirmados, para la lista del administrador (paso 2). recentRecords cuenta
 * reclamaciones y decisiones de moderación de los últimos {@link #RECENT_DAYS} días.
 */
public record StoreRecordSummary(Long storeId, String storeName, String storeStatus, long claims, long moderations,
                                 long suspensions, long recentRecords, LocalDateTime lastRecordAt,
                                 boolean activeSuspension, boolean openClarification) {

    public static final int RECENT_DAYS = 180;

    /**
     * Reincidencia (paso 5): dos o más antecedentes recientes, o una suspensión anterior. Es una ayuda para evaluar;
     * la decisión siempre es del administrador.
     */
    public static boolean recidivist(long recentRecords, long suspensions) {
        return recentRecords >= 2 || suspensions >= 1;
    }

    public boolean recidivist() {
        return recidivist(recentRecords, suspensions);
    }
}
