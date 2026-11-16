package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Buscar una cuenta por su correo exacto (paso 2). */
class SearchAccountsUseCaseTests {

    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final SearchAccountsUseCase useCase = new SearchAccountsUseCase(accounts);

    @Test
    void returnsTheAccountFoundByEmail() {
        when(accounts.findByEmail("cuenta@example.com")).thenReturn(Optional.of(
                new AccountDirectoryEntry(7L, "cuenta@example.com", true, Set.of(Role.COMPRADOR))));

        Optional<AccountDirectoryEntry> result = useCase.execute("cuenta@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(7L);
    }

    @Test
    void isEmptyWhenNoAccountMatches() {
        when(accounts.findByEmail("nadie@example.com")).thenReturn(Optional.empty());

        assertThat(useCase.execute("nadie@example.com")).isEmpty();
    }
}
