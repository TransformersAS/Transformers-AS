package com.transformersas.marketplace.users.infrastructure.persistence.mapper;

import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.infrastructure.persistence.entity.UserAccountEntity;
import java.util.HashSet;

public final class UserAccountMapper {
    private UserAccountMapper() {}

    public static UserAccountEntity toEntity(UserAccount account) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setId(account.id());
        entity.setEmail(account.email());
        entity.setPasswordHash(account.passwordHash());
        entity.setStatus(account.status());
        entity.setRoles(new HashSet<>(account.roles()));
        return entity;
    }

    public static UserAccount toDomain(UserAccountEntity entity) {
        return new UserAccount(entity.getId(), entity.getEmail(), entity.getPasswordHash(),
                entity.getStatus(), entity.getRoles());
    }
}
