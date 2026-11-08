package com.transformersas.marketplace.suspensions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Suspensión temporal de la operación comercial de una tienda (RF-170). Guarda motivo, inicio, fin y administrador
 * responsable; al cerrarse, cuándo, quién (nulo si terminó sola) y por qué. version permite detectar que dos
 * administradores, o el barrido, la cambiaron a la vez.
 */
public record StoreSuspension(Long id, Long storeId, String reason, LocalDateTime startsAt, LocalDateTime endsAt,
                              SuspensionStatus status, Long appliedBy, LocalDateTime closedAt, Long closedBy,
                              String closeReason, long version) {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 365;

    /** Nueva suspensión que empieza ahora y dura {@code days} días. Valida motivo y duración (paso 11). */
    public static StoreSuspension start(Long storeId, String reason, Integer days, Long adminId, LocalDateTime now) {
        String text = Texts.required(reason, 10, 1000, "SUSPENSION_REASON_REQUIRED", "reason", "El motivo");
        int duration = validDays(days);
        return new StoreSuspension(null, storeId, text, now, now.plusDays(duration), SuspensionStatus.ACTIVE, adminId,
                null, null, null, 0);
    }

    /** Fin que tendría con otra duración total, contada desde su inicio (A3). */
    public LocalDateTime endWith(Integer days, LocalDateTime now) {
        requireActive();
        LocalDateTime newEnd = startsAt.plusDays(validDays(days));
        if (newEnd.equals(endsAt)) {
            throw invalid("SUSPENSION_DURATION_UNCHANGED", "La suspensión ya tiene esa duración", "durationDays");
        }
        if (!newEnd.isAfter(now)) {
            throw invalid("SUSPENSION_END_IN_PAST", "Con esa duración la suspensión ya habría terminado; para "
                    + "levantarla ahora usa Finalizar anticipadamente", "durationDays");
        }
        return newEnd;
    }

    public boolean isActive() {
        return status == SuspensionStatus.ACTIVE;
    }

    /** Vigente y con la fecha de fin ya cumplida: le toca terminar sola (A5). */
    public boolean isDue(LocalDateTime now) {
        return isActive() && !endsAt.isAfter(now);
    }

    public void requireActive() {
        if (!isActive()) {
            throw BusinessException.conflict("SUSPENSION_NOT_ACTIVE", "La suspensión ya terminó");
        }
    }

    private static int validDays(Integer days) {
        if (days == null || days < MIN_DAYS || days > MAX_DAYS) {
            throw invalid("SUSPENSION_DURATION_INVALID",
                    "La duración debe ser de " + MIN_DAYS + " a " + MAX_DAYS + " días", "durationDays");
        }
        return days;
    }

    private static BusinessException invalid(String code, String message, String field) {
        return new BusinessException(BusinessException.Kind.INVALID, code, message, Map.of("field", field));
    }
}
