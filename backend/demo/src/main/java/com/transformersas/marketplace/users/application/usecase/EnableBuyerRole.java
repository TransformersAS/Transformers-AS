package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import com.transformersas.marketplace.users.domain.repository.BuyerRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnableBuyerRole {
    public record Result(boolean buyerRoleAdded, String message) { }
    private final UserAccountRepository accounts;
    private final BuyerRegistrationRepository roles;
    public EnableBuyerRole(UserAccountRepository accounts, BuyerRegistrationRepository roles) { this.accounts=accounts; this.roles=roles; }
    @Transactional
    public Result execute(Long id, boolean terms, boolean privacy) {
        if (!terms || !privacy) throw BusinessException.invalid("BUYER_CONSENT_REQUIRED", "Acepta los términos y la política de privacidad");
        var account=accounts.findById(id).orElseThrow(() -> BusinessException.unauthenticated("ACCOUNT_NOT_FOUND", "La cuenta no existe"));
        if (account.status()!=AccountStatus.ACTIVA || !accounts.isEmailVerified(id))
            throw BusinessException.forbidden("ACCOUNT_NOT_AVAILABLE", "La cuenta debe estar activa y su correo verificado");
        boolean added=roles.addBuyerRole(id);
        if (added) roles.consent(id, BuyerTerms.VERSION);
        return new Result(added, added ? "Rol COMPRADOR habilitado. Puedes seleccionarlo desde tu cuenta."
                : "Tu cuenta ya tiene COMPRADOR; no necesitas registrarte nuevamente.");
    }
}
