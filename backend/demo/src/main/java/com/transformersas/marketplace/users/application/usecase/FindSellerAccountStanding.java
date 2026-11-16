package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.port.AccountRestrictionReader;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Si una cuenta puede operar como vendedora: está activa, conserva el rol VENDEDOR y no tiene una restricción
 * vigente sobre ese rol o sobre toda la cuenta (CU-22). Impide reactivar una tienda al terminar su suspensión
 * (CU-27, A6). No expone credenciales ni datos personales: solo la conclusión.
 */
@Service
public class FindSellerAccountStanding {

    public record Standing(boolean active, boolean seller, boolean restricted) {
        public boolean canSell() {
            return active && seller && !restricted;
        }
    }

    private final UserAccountRepository accounts;
    private final AccountRestrictionReader restrictions;

    public FindSellerAccountStanding(UserAccountRepository accounts, AccountRestrictionReader restrictions) {
        this.accounts = accounts;
        this.restrictions = restrictions;
    }

    /** Vacío si la cuenta no existe. */
    @Transactional(readOnly = true)
    public Optional<Standing> execute(Long accountId) {
        return accounts.findById(accountId).map(account -> new Standing(account.status() == AccountStatus.ACTIVA,
                account.roles().contains(Role.VENDEDOR), restrictions.isRestricted(accountId, Role.VENDEDOR)));
    }
}
