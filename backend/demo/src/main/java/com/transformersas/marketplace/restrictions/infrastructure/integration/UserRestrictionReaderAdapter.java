package com.transformersas.marketplace.restrictions.infrastructure.integration;

import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.port.AccountRestrictionReader;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implementa para {@code users} el puerto de restricciones vigentes, con la persistencia de este módulo. */
@Component
class UserRestrictionReaderAdapter implements AccountRestrictionReader {

    private final AccountRestrictionRepository restrictions;

    UserRestrictionReaderAdapter(AccountRestrictionRepository restrictions) {
        this.restrictions = restrictions;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRestricted(Long accountId, Role role) {
        return restrictions.hasActiveRestriction(accountId, role);
    }
}
