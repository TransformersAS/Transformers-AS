package com.transformersas.marketplace.restrictions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.Role;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Restricción administrativa sobre una cuenta (CU-22): sobre toda la cuenta o sobre uno de sus roles (paso 8),
 * temporal con fecha de fin o permanente. Guarda motivo, alcance, administrador responsable y duración; al cerrarse,
 * cuándo, quién (nulo si terminó sola) y por qué. version permite detectar que dos administradores, o el barrido, la
 * cambiaron a la vez.
 *
 * @param role rol afectado cuando {@code scope} es {@link RestrictionScope#ROLE}; nulo cuando es
 *             {@link RestrictionScope#ACCOUNT}
 */
public record AccountRestriction(Long id, Long accountId, RestrictionScope scope, Role role, String reason,
                                 boolean permanent, LocalDateTime startsAt, LocalDateTime endsAt,
                                 RestrictionStatus status, Long appliedBy, LocalDateTime closedAt, Long closedBy,
                                 String closeReason, long version) {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 3650;

    /** Nueva restricción que empieza ahora. Valida alcance, motivo y duración (pasos 9 a 12). */
    public static AccountRestriction start(Long accountId, RestrictionScope scope, Role role, String reason,
                                           boolean permanent, Integer days, Long adminId, LocalDateTime now) {
        requireScopeMatchesRole(scope, role);
        String text = Texts.required(reason, 10, 1000, "RESTRICTION_REASON_REQUIRED", "reason", "El motivo");
        LocalDateTime endsAt = permanent ? null : now.plusDays(validDays(days));
        return new AccountRestriction(null, accountId, scope, role, text, permanent, now, endsAt,
                RestrictionStatus.ACTIVE, adminId, null, null, null, 0);
    }

    /** Fin que tendría con otra duración total, contada desde su inicio (A7). Exige que siga siendo temporal. */
    public LocalDateTime endWith(Integer days, LocalDateTime now) {
        requireActive();
        requireTemporary();
        LocalDateTime newEnd = startsAt.plusDays(validDays(days));
        if (newEnd.equals(endsAt)) {
            throw invalid("RESTRICTION_DURATION_UNCHANGED", "La restricción ya tiene esa duración", "durationDays");
        }
        if (!newEnd.isAfter(now)) {
            throw invalid("RESTRICTION_END_IN_PAST",
                    "Con esa duración la restricción ya habría terminado; para retirarla ahora usa Retirar "
                            + "anticipadamente", "durationDays");
        }
        return newEnd;
    }

    public boolean isActive() {
        return status == RestrictionStatus.ACTIVE;
    }

    /** Vigente, temporal y con la fecha de fin ya cumplida: le toca terminar sola (A5). */
    public boolean isDue(LocalDateTime now) {
        return isActive() && !permanent && !endsAt.isAfter(now);
    }

    public void requireActive() {
        if (!isActive()) {
            throw BusinessException.conflict("RESTRICTION_NOT_ACTIVE", "La restricción ya terminó");
        }
    }

    private void requireTemporary() {
        if (permanent) {
            throw BusinessException.conflict("RESTRICTION_IS_PERMANENT",
                    "La restricción es permanente; conviértela en temporal antes de cambiar su duración");
        }
    }

    private static void requireScopeMatchesRole(RestrictionScope scope, Role role) {
        boolean hasRole = role != null;
        if ((scope == RestrictionScope.ROLE) != hasRole) {
            throw invalid("RESTRICTION_SCOPE_ROLE_MISMATCH",
                    scope == RestrictionScope.ROLE ? "Indica el rol que se restringe"
                            : "Una restricción de toda la cuenta no lleva un rol", "role");
        }
    }

    private static int validDays(Integer days) {
        if (days == null || days < MIN_DAYS || days > MAX_DAYS) {
            throw invalid("RESTRICTION_DURATION_INVALID",
                    "La duración debe ser de " + MIN_DAYS + " a " + MAX_DAYS + " días", "durationDays");
        }
        return days;
    }

    private static BusinessException invalid(String code, String message, String field) {
        return new BusinessException(BusinessException.Kind.INVALID, code, message, Map.of("field", field));
    }
}
