package com.transformersas.marketplace.users;

import com.transformersas.marketplace.users.application.usecase.FindSellerAccountStanding;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.port.AccountRestrictionReader;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Si una cuenta puede operar como vendedora (RF-062, CU-22): activa, con el rol VENDEDOR y sin una restricción
 * vigente sobre ese rol o sobre toda la cuenta.
 */
class FindSellerAccountStandingTests {

    private static final String HASH = "$2a$04$usRG4rcIaLvbUXFRUHbOne6Jv2m0TLim9TuCmNvAoBEqlGzcEbpTS";

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccountRestrictionReader restrictions = mock(AccountRestrictionReader.class);
    private final FindSellerAccountStanding useCase = new FindSellerAccountStanding(accounts, restrictions);

    private static UserAccount seller(AccountStatus status) {
        return new UserAccount(7L, "vendedor@example.com", HASH, status, Set.of(Role.VENDEDOR));
    }

    @Test
    void anActiveSellerWithoutRestrictionsCanSell() {
        when(accounts.findById(7L)).thenReturn(Optional.of(seller(AccountStatus.ACTIVA)));
        when(restrictions.isRestricted(7L, Role.VENDEDOR)).thenReturn(false);

        var standing = useCase.execute(7L).orElseThrow();

        assertThat(standing.canSell()).isTrue();
    }

    @Test
    void aRestrictedSellerCannotSellEvenIfActive() {
        when(accounts.findById(7L)).thenReturn(Optional.of(seller(AccountStatus.ACTIVA)));
        when(restrictions.isRestricted(7L, Role.VENDEDOR)).thenReturn(true);

        var standing = useCase.execute(7L).orElseThrow();

        assertThat(standing.active()).isTrue();
        assertThat(standing.seller()).isTrue();
        assertThat(standing.restricted()).isTrue();
        assertThat(standing.canSell()).isFalse();
    }

    @Test
    void anInactiveAccountCannotSellRegardlessOfRestrictions() {
        when(accounts.findById(7L)).thenReturn(Optional.of(seller(AccountStatus.INACTIVA)));
        when(restrictions.isRestricted(7L, Role.VENDEDOR)).thenReturn(false);

        assertThat(useCase.execute(7L).orElseThrow().canSell()).isFalse();
    }

    @Test
    void anUnknownAccountIsEmpty() {
        when(accounts.findById(999L)).thenReturn(Optional.empty());

        assertThat(useCase.execute(999L)).isEmpty();
    }
}
