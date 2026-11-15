package com.transformersas.marketplace.restrictions.infrastructure.integration;

import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Busca cuentas con el repositorio de {@code users}, sin exponer su contraseña. */
@Component
class UserAccountDirectoryAdapter implements AccountDirectory {

    private final UserAccountRepository accounts;

    UserAccountDirectoryAdapter(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<AccountDirectoryEntry> findByEmail(String email) {
        return accounts.findByEmail(email).map(UserAccountDirectoryAdapter::toEntry);
    }

    @Override
    public Optional<AccountDirectoryEntry> findById(Long accountId) {
        return accounts.findById(accountId).map(UserAccountDirectoryAdapter::toEntry);
    }

    private static AccountDirectoryEntry toEntry(UserAccount account) {
        return new AccountDirectoryEntry(account.id(), account.email(), account.status() == AccountStatus.ACTIVA,
                account.roles());
    }
}
