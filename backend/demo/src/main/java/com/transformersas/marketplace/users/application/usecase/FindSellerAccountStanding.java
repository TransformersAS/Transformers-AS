package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Si una cuenta puede operar como vendedora: está activa y conserva el rol VENDEDOR. Una medida sobre la cuenta o el
 * rol (CU-22) impide reactivar una tienda al terminar su suspensión (CU-27, A6). No expone credenciales ni datos
 * personales: solo la conclusión.
 */
@Service
public class FindSellerAccountStanding {

    public record Standing(boolean active, boolean seller) {
        public boolean canSell() {
            return active && seller;
        }
    }

    private final UserAccountRepository accounts;

    public FindSellerAccountStanding(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Vacío si la cuenta no existe. */
    @Transactional(readOnly = true)
    public Optional<Standing> execute(Long accountId) {
        return accounts.findById(accountId).map(account -> new Standing(account.status() == AccountStatus.ACTIVA,
                account.roles().contains(Role.VENDEDOR)));
    }
}
