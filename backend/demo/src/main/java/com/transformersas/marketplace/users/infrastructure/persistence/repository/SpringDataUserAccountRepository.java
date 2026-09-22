package com.transformersas.marketplace.users.infrastructure.persistence.repository;

import com.transformersas.marketplace.users.infrastructure.persistence.entity.UserAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface SpringDataUserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
    @Query(value = "SELECT COUNT(*) FROM user_accounts WHERE id = :id AND email_verified_at IS NOT NULL", nativeQuery = true)
    int countVerifiedEmail(Long id);

    @Modifying
    @Query(value = "UPDATE user_accounts SET email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP(6)) WHERE id = :id", nativeQuery = true)
    void markEmailVerified(Long id);
    Optional<UserAccountEntity> findByEmail(String email);
}
