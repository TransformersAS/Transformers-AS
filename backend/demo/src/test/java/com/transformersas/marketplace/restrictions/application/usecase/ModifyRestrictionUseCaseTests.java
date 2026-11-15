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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A7: cambiar la duración o la permanencia de una restricción vigente, siempre con justificación. El caso de uso
 * calcula "ahora" con el reloj real, así que las fechas de los accesorios se anclan a {@link LocalDateTime#now()},
 * no a una fecha fija, para no depender de cuándo corre la prueba.
 */
class ModifyRestrictionUseCaseTests {

    private static final LocalDateTime STARTED = LocalDateTime.now().minusDays(3);

    private final AccountRestrictionRepository restrictions = mock(AccountRestrictionRepository.class);
    private final RestrictionAudit audit = mock(RestrictionAudit.class);
    private final RestrictionNotices notices = mock(RestrictionNotices.class);
    private final ModifyRestrictionUseCase useCase = new ModifyRestrictionUseCase(restrictions, audit, notices);

    private static AccountRestriction temporary(boolean permanent, LocalDateTime endsAt) {
        return new AccountRestriction(42L, 7L, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", permanent, STARTED, endsAt, RestrictionStatus.ACTIVE, 1L,
                null, null, null, 3);
    }

    @Test
    void changeDurationRequiresAJustification() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(temporary(false, STARTED.plusDays(7))));

        assertThatThrownBy(() -> useCase.changeDuration(1L, 42L, 14, "corto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("JUSTIFICATION_REQUIRED"));
    }

    @Test
    void changeDurationFailsOnAConcurrentUpdate() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(temporary(false, STARTED.plusDays(7))));
        when(restrictions.changeEnd(42L, 3, STARTED.plusDays(14))).thenReturn(false);

        assertThatThrownBy(() -> useCase.changeDuration(1L, 42L, 14, "Apareció una reincidencia nueva"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("RESTRICTION_CONCURRENT_UPDATE");
                });
    }

    @Test
    void makingAPermanentRestrictionPermanentAgainIsRejected() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(temporary(true, null)));

        assertThatThrownBy(() -> useCase.makePermanent(1L, 42L, "Justificación suficiente"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("RESTRICTION_ALREADY_PERMANENT");
                });
    }

    @Test
    void makingATemporaryRestrictionTemporaryAgainIsRejected() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(temporary(false, STARTED.plusDays(7))));

        assertThatThrownBy(() -> useCase.makeTemporary(1L, 42L, 30, "Justificación suficiente"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("RESTRICTION_ALREADY_TEMPORARY");
                });
    }

    @Test
    void makePermanentClearsTheEndDate() {
        when(restrictions.findById(42L)).thenReturn(Optional.of(temporary(false, STARTED.plusDays(7))))
                .thenReturn(Optional.of(temporary(true, null)));
        when(restrictions.makePermanent(42L, 3)).thenReturn(true);

        AccountRestriction updated = useCase.makePermanent(1L, 42L, "Se agravó la situación");

        assertThat(updated.permanent()).isTrue();
    }

    @Test
    void anUnknownRestrictionIsNotFound() {
        when(restrictions.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.changeDuration(1L, 999L, 10, "Justificación suficiente"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("RESTRICTION_NOT_FOUND"));
    }
}
