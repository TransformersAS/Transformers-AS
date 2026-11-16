package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cierra una restricción y decide si libera la tienda de la cuenta (pasos 19 y 20). */
class RestrictionClosingTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);

    private final AccountRestrictionRepository restrictions = mock(AccountRestrictionRepository.class);
    private final StoreOperationalLink storeLink = mock(StoreOperationalLink.class);
    private final RestrictionAudit audit = mock(RestrictionAudit.class);
    private final RestrictionNotices notices = mock(RestrictionNotices.class);
    private final RestrictionClosing closing = new RestrictionClosing(restrictions, storeLink, audit, notices);

    private static AccountRestriction restriction(RestrictionScope scope, Role role) {
        return new AccountRestriction(42L, 7L, scope, role, "Incumplimiento reiterado de las reglas", false, T0,
                T0.plusDays(30), RestrictionStatus.ACTIVE, 1L, null, null, null, 0);
    }

    @Test
    void closingTheOnlyRestrictionThatBlockedSellingReleasesTheStore() {
        AccountRestriction active = restriction(RestrictionScope.ACCOUNT, null);
        when(restrictions.close(42L, 0, RestrictionStatus.WITHDRAWN, T0, 1L, "Se corrigió el problema"))
                .thenReturn(true);
        when(restrictions.hasActiveRestriction(7L, Role.VENDEDOR)).thenReturn(false);
        when(storeLink.storeOwnedBy(7L)).thenReturn(Optional.of(5L));

        closing.close(active, RestrictionStatus.WITHDRAWN, 1L, "Se corrigió el problema", T0);

        verify(storeLink).endRestriction(5L);
        verify(notices).restrictionEnded(active, true);
    }

    @Test
    void anotherStillActiveRestrictionKeepsTheStoreRestricted() {
        AccountRestriction active = restriction(RestrictionScope.ROLE, Role.VENDEDOR);
        when(restrictions.close(42L, 0, RestrictionStatus.WITHDRAWN, T0, 1L, "Se corrigió el problema"))
                .thenReturn(true);
        // Queda otra restricción vigente (p.ej. de toda la cuenta) que también le impide vender.
        when(restrictions.hasActiveRestriction(7L, Role.VENDEDOR)).thenReturn(true);

        closing.close(active, RestrictionStatus.WITHDRAWN, 1L, "Se corrigió el problema", T0);

        verify(storeLink, never()).storeOwnedBy(anyLong());
        verify(storeLink, never()).endRestriction(anyLong());
    }

    @Test
    void aBuyerRoleRestrictionNeverTouchesTheStoreWhenClosed() {
        AccountRestriction active = restriction(RestrictionScope.ROLE, Role.COMPRADOR);
        when(restrictions.close(42L, 0, RestrictionStatus.WITHDRAWN, T0, 1L, "Se corrigió el problema"))
                .thenReturn(true);

        closing.close(active, RestrictionStatus.WITHDRAWN, 1L, "Se corrigió el problema", T0);

        verify(restrictions, never()).hasActiveRestriction(anyLong(), any());
        verify(storeLink, never()).storeOwnedBy(anyLong());
    }

    @Test
    void aConcurrentCloseIsReportedAsAConflict() {
        when(restrictions.close(42L, 0, RestrictionStatus.WITHDRAWN, T0, 1L, "Se corrigió el problema"))
                .thenReturn(false);
        AccountRestriction active = restriction(RestrictionScope.ACCOUNT, null);

        assertThatThrownBy(() -> closing.close(active, RestrictionStatus.WITHDRAWN, 1L, "Se corrigió el problema",
                T0)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("RESTRICTION_CONCURRENT_UPDATE"));
        verify(audit, never()).record(any(), any(), any(), any());
    }
}
