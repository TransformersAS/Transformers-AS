package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** Read-only application boundary for authentication; exposes no persistence implementation. */
@Service
public class FindAccountByEmail {
    private final UserAccountRepository accounts;

    public FindAccountByEmail(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    public Optional<UserAccount> execute(String email) {
        return accounts.findByEmail(email);
    }
}
