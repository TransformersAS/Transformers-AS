package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A6: el administrador retira una restricción antes de su fin, con un motivo. */
class WithdrawRestrictionUseCaseTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);

    private final AccountRestrictionRepository restrictions = mock(AccountRestrictionRepository.class);
    private final RestrictionClosing closing = mock(RestrictionClosing.class);
    private final WithdrawRestrictionUseCase useCase = new WithdrawRestrictionUseCase(restrictions, closing);

    private static AccountRestriction active() {
        return new AccountRestriction(42L, 7L, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, T0, T0.plusDays(30), RestrictionStatus.ACTIVE, 1L,
                null, null, null, 0);
    }

    @Test
    void anUnknownRestrictionIsNotFound() {
        when(restrictions.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(1L, 999L, "El usuario corrigió el problema"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("RESTRICTION_NOT_FOUND"));
    }

    @Test
    void anAlreadyClosedRestrictionCannotBeWithdrawnAgain() {
        AccountRestriction withdrawn = new AccountRestriction(42L, 7L, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, T0, T0.plusDays(30), RestrictionStatus.WITHDRAWN,
                1L, T0.plusDays(1), 1L, "Ya retirada", 1);
        when(restrictions.findById(42L)).thenReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> useCase.execute(1L, 42L, "Otra vez"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("RESTRICTION_NOT_ACTIVE"));
    }

    @Test
    void theReasonIsRequiredAndDelegatesToClosing() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(active()));

        assertThatThrownBy(() -> useCase.execute(1L, 42L, "corto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("JUSTIFICATION_REQUIRED"));

        useCase.execute(1L, 42L, "El usuario corrigió el problema reportado");
        verify(closing).close(any(), eq(RestrictionStatus.WITHDRAWN), eq(1L),
                eq("El usuario corrigió el problema reportado"), any());
    }
}
