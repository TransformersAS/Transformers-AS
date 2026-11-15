package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** El administrador busca una cuenta por su correo exacto (paso 2) para revisarla o restringirla. */
@Service
public class SearchAccountsUseCase {

    private final AccountDirectory accounts;

    public SearchAccountsUseCase(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Optional<AccountDirectoryEntry> execute(String email) {
        return accounts.findByEmail(email);
    }
}
