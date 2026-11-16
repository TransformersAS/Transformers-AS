package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;
import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Aplica una restricción y, cuando corresponde, su efecto sobre la tienda de la cuenta (postcondición 5). */
class ApplyRestrictionUseCaseTests {

    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountRestrictionRepository restrictions = mock(AccountRestrictionRepository.class);
    private final StoreOperationalLink storeLink = mock(StoreOperationalLink.class);
    private final RestrictionAudit audit = mock(RestrictionAudit.class);
    private final RestrictionNotices notices = mock(RestrictionNotices.class);
    private final ApplyRestrictionUseCase useCase = new ApplyRestrictionUseCase(accounts, restrictions, storeLink,
            audit, notices);

    private static AccountRestriction savedRestriction(RestrictionScope scope, Role role) {
        return new AccountRestriction(42L, 7L, scope, role, "Incumplimiento reiterado de las reglas", false,
                LocalDateTime.now(), LocalDateTime.now().plusDays(30), RestrictionStatus.ACTIVE, 1L, null, null,
                null, 0);
    }

    private void givenExistingAccountAndSavedRestriction(RestrictionScope scope, Role role) {
        when(accounts.findById(7L)).thenReturn(Optional.of(new AccountDirectoryEntry(7L, "cuenta@example.com", true,
                Set.of(Role.VENDEDOR))));
        when(restrictions.insert(any())).thenReturn(42L);
        when(restrictions.findById(42L)).thenReturn(Optional.of(savedRestriction(scope, role)));
    }

    @Test
    void anUnknownAccountIsRejectedBeforeCreatingAnything() {
        when(accounts.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(1L, 7L, RestrictionScope.ACCOUNT, null, "Motivo suficiente", false,
                30)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("ACCOUNT_NOT_FOUND"));
        verify(restrictions, never()).insert(any());
    }

    @Test
    void anAccountScopedRestrictionRestrictsTheOwnedStore() {
        givenExistingAccountAndSavedRestriction(RestrictionScope.ACCOUNT, null);
        when(storeLink.storeOwnedBy(7L)).thenReturn(Optional.of(5L));

        AccountRestriction result = useCase.execute(1L, 7L, RestrictionScope.ACCOUNT, null,
                "Incumplimiento reiterado de las reglas", false, 30);

        assertThat(result.id()).isEqualTo(42L);
        verify(storeLink).restrict(5L, "Incumplimiento reiterado de las reglas");
        verify(restrictions).addChange(any(RestrictionChange.class));
        verify(audit).record(eq(1L), eq("ACCOUNT_RESTRICTION_APPLIED"), eq(7L), any());
        verify(notices).restrictionApplied(any(), eq(42L));
    }

    @Test
    void aSellerRoleRestrictionAlsoRestrictsTheStore() {
        givenExistingAccountAndSavedRestriction(RestrictionScope.ROLE, Role.VENDEDOR);
        when(storeLink.storeOwnedBy(7L)).thenReturn(Optional.of(5L));

        useCase.execute(1L, 7L, RestrictionScope.ROLE, Role.VENDEDOR, "Incumplimiento reiterado de las reglas",
                false, 30);

        verify(storeLink).restrict(eq(5L), any());
    }

    @Test
    void aBuyerRoleRestrictionNeverTouchesAStore() {
        givenExistingAccountAndSavedRestriction(RestrictionScope.ROLE, Role.COMPRADOR);

        useCase.execute(1L, 7L, RestrictionScope.ROLE, Role.COMPRADOR, "Incumplimiento reiterado de las reglas",
                false, 30);

        verify(storeLink, never()).storeOwnedBy(any());
        verify(storeLink, never()).restrict(anyLong(), any());
    }

    @Test
    void anAccountWithoutAStoreIsNotRestrictedAtTheStoreLevel() {
        givenExistingAccountAndSavedRestriction(RestrictionScope.ACCOUNT, null);
        when(storeLink.storeOwnedBy(7L)).thenReturn(Optional.empty());

        useCase.execute(1L, 7L, RestrictionScope.ACCOUNT, null, "Incumplimiento reiterado de las reglas", false, 30);

        verify(storeLink, never()).restrict(anyLong(), any());
    }
}
