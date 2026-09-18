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
    private final SpringDataUserAccountRepository repository;

    public JpaUserAccountRepository(SpringDataUserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UserAccount save(UserAccount account) {
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
