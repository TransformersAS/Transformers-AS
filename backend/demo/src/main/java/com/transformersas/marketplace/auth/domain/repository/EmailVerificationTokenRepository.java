package com.transformersas.marketplace.auth.domain.repository;

import java.util.Optional;

public interface EmailVerificationTokenRepository {
    void lockAccount(Long accountId);
    void replace(Long accountId, String tokenHash);
    Optional<Long> findAccountId(String tokenHash);
    boolean consume(String tokenHash);
}
