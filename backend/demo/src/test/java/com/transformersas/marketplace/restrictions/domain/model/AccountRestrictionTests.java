package com.transformersas.marketplace.restrictions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La máquina de estados de una restricción (CU-22): alcance, temporalidad y sus transiciones. */
class AccountRestrictionTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final Long ACCOUNT = 7L;
    private static final Long ADMIN = 1L;

    private static void assertInvalid(Runnable action, String code, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.kind()).isEqualTo(BusinessException.Kind.INVALID);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.details()).isEqualTo(Map.of("field", field));
        });
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    // ---------- Creación ----------

    @Test
    void anAccountScopedRestrictionHasNoRole() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 30, ADMIN, T0);

        assertThat(restriction.scope()).isEqualTo(RestrictionScope.ACCOUNT);
        assertThat(restriction.role()).isNull();
        assertThat(restriction.permanent()).isFalse();
        assertThat(restriction.startsAt()).isEqualTo(T0);
        assertThat(restriction.endsAt()).isEqualTo(T0.plusDays(30));
        assertThat(restriction.status()).isEqualTo(RestrictionStatus.ACTIVE);
        assertThat(restriction.appliedBy()).isEqualTo(ADMIN);
    }

    @Test
    void aRoleScopedRestrictionRequiresTheRole() {
        assertInvalid(() -> AccountRestriction.start(ACCOUNT, RestrictionScope.ROLE, null,
                "Incumplimiento reiterado de las reglas", false, 30, ADMIN, T0),
                "RESTRICTION_SCOPE_ROLE_MISMATCH", "role");
    }

    @Test
    void anAccountScopedRestrictionCannotCarryARole() {
        assertInvalid(() -> AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, Role.VENDEDOR,
                "Incumplimiento reiterado de las reglas", false, 30, ADMIN, T0),
                "RESTRICTION_SCOPE_ROLE_MISMATCH", "role");
    }

    @Test
    void aPermanentRestrictionHasNoEndDate() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ROLE, Role.VENDEDOR,
                "Fraude confirmado en varias ventas", true, null, ADMIN, T0);

        assertThat(restriction.permanent()).isTrue();
        assertThat(restriction.endsAt()).isNull();
    }

    @Test
    void theReasonIsRequired() {
        assertInvalid(() -> AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null, "corto", false, 30,
                ADMIN, T0), "RESTRICTION_REASON_REQUIRED", "reason");
    }

    @Test
    void aTemporaryRestrictionNeedsAValidDuration() {
        assertInvalid(() -> AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 0, ADMIN, T0),
                "RESTRICTION_DURATION_INVALID", "durationDays");
        assertInvalid(() -> AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, AccountRestriction.MAX_DAYS + 1, ADMIN, T0),
                "RESTRICTION_DURATION_INVALID", "durationDays");
    }

    // ---------- A7: cambiar duración ----------

    @Test
    void endWithChangesTheEndDateCountedFromTheStart() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 7, ADMIN, T0);

        LocalDateTime newEnd = restriction.endWith(14, T0.plusHours(1));

        assertThat(newEnd).isEqualTo(T0.plusDays(14));
    }

    @Test
    void endWithRejectsTheSameDuration() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 7, ADMIN, T0);

        assertInvalid(() -> restriction.endWith(7, T0.plusHours(1)), "RESTRICTION_DURATION_UNCHANGED",
                "durationDays");
    }

    @Test
    void endWithRejectsADurationAlreadyInThePast() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 7, ADMIN, T0);

        assertInvalid(() -> restriction.endWith(1, T0.plusDays(5)), "RESTRICTION_END_IN_PAST", "durationDays");
    }

    @Test
    void endWithRequiresTheRestrictionToBeTemporary() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", true, null, ADMIN, T0);

        assertConflict(() -> restriction.endWith(30, T0), "RESTRICTION_IS_PERMANENT");
    }

    // ---------- Estado ----------

    @Test
    void isDueOnlyForTemporaryActiveRestrictionsPastTheirEnd() {
        AccountRestriction temporary = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 7, ADMIN, T0);
        AccountRestriction permanent = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", true, null, ADMIN, T0);

        assertThat(temporary.isDue(T0.plusDays(6))).isFalse();
        assertThat(temporary.isDue(T0.plusDays(7))).isTrue();
        assertThat(permanent.isDue(T0.plusYears(10))).isFalse();
    }

    @Test
    void requireActiveRejectsAClosedRestriction() {
        AccountRestriction restriction = AccountRestriction.start(ACCOUNT, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 7, ADMIN, T0);
        AccountRestriction closed = new AccountRestriction(restriction.id(), restriction.accountId(),
                restriction.scope(), restriction.role(), restriction.reason(), restriction.permanent(),
                restriction.startsAt(), restriction.endsAt(), RestrictionStatus.WITHDRAWN, restriction.appliedBy(),
                T0.plusDays(1), ADMIN, "Se corrigió el problema", restriction.version());

        assertConflict(closed::requireActive, "RESTRICTION_NOT_ACTIVE");
    }
}
