package com.transformersas.marketplace.users.domain.repository;

import com.transformersas.marketplace.users.domain.model.UserAccount;
import java.util.Optional;

public interface UserAccountRepository {
    UserAccount save(UserAccount account);
    Optional<UserAccount> findById(Long id);
    Optional<UserAccount> findByEmail(String email);
}
