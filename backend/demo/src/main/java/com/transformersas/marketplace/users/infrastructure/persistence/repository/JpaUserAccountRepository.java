package com.transformersas.marketplace.users.infrastructure.persistence.repository;

import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import com.transformersas.marketplace.users.infrastructure.persistence.mapper.UserAccountMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class JpaUserAccountRepository implements UserAccountRepository {
    @Override
    public boolean isEmailVerified(Long id) {
        return repository.countVerifiedEmail(id) == 1;
    }

    @Override
    @Transactional
    public void markEmailVerified(Long id) {
        repository.markEmailVerified(id);
    }
    private final SpringDataUserAccountRepository repository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public JpaUserAccountRepository(SpringDataUserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UserAccount save(UserAccount account) {
        // Los guardados de roles/credenciales no deben restaurar la identidad anterior a CU09.
        if (account.id() != null) {
            var rows = entityManager.createNativeQuery("SELECT email,status FROM user_accounts WHERE id=? FOR UPDATE")
                    .setParameter(1, account.id()).getResultList();
            if (!rows.isEmpty()) {
                Object[] current = (Object[]) rows.getFirst();
                if (!account.email().equals(current[0]) || !account.status().name().equals(current[1])) {
                    throw com.transformersas.marketplace.shared.error.BusinessException.conflict(
                            "ACCOUNT_CHANGED", "La cuenta cambió durante la operación. Inicia sesión nuevamente.");
                }
            }
        }
        return UserAccountMapper.toDomain(repository.saveAndFlush(UserAccountMapper.toEntity(account)));
    }

    @Override
    public Optional<UserAccount> findById(Long id) {
        return repository.findById(id).map(UserAccountMapper::toDomain);
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        return repository.findByEmail(email.strip().toLowerCase(Locale.ROOT)).map(UserAccountMapper::toDomain);
    }
}
