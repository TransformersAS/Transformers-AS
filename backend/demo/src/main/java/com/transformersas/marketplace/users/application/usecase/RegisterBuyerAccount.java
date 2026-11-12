package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.auth.application.usecase.PasswordPolicy;
import com.transformersas.marketplace.auth.application.usecase.VerifyAccountEmail;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.*;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import com.transformersas.marketplace.users.domain.repository.BuyerRegistrationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Set;

@Service
public class RegisterBuyerAccount {
    public record Input(String fullName, java.time.LocalDate birthDate, String email, String phone,
                        String password, String passwordConfirmation, boolean acceptTerms, boolean acceptPrivacy) {
        @Override public String toString() { return "BuyerRegistrationInput[REDACTED]"; }
    }
    public record Result(boolean emailVerificationRequired, boolean verificationDeliveryFailed, String message) { }
    private final UserAccountRepository accounts;
    private final BuyerRegistrationRepository profiles;
    private final PasswordEncoder encoder;
    private final VerifyAccountEmail verification;
    private final TransactionTemplate transaction;
    public RegisterBuyerAccount(UserAccountRepository accounts, BuyerRegistrationRepository profiles,
            PasswordEncoder encoder, VerifyAccountEmail verification, PlatformTransactionManager transactions) {
        this.accounts=accounts; this.profiles=profiles; this.encoder=encoder; this.verification=verification;
        this.transaction=new TransactionTemplate(transactions);
    }
    public Result execute(Input request) {
        if (!request.acceptTerms() || !request.acceptPrivacy())
            throw BusinessException.invalid("BUYER_CONSENT_REQUIRED", "Acepta los términos y la política de privacidad");
        if (!request.password().equals(request.passwordConfirmation()))
            throw BusinessException.invalid("PASSWORD_CONFIRMATION_MISMATCH", "Las contraseñas no coinciden");
        PasswordPolicy.validate(request.password());
        try {
            transaction.executeWithoutResult(status -> {
                if (accounts.findByEmail(request.email()).isPresent()) throw existing();
                var account=accounts.save(new UserAccount(null, request.email(), encoder.encode(request.password()), AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
                profiles.saveProfile(account.id(), request.fullName(), request.birthDate(), request.phone());
                profiles.consent(account.id(), BuyerTerms.VERSION);
            });
        } catch (DataIntegrityViolationException duplicate) {
            if (accounts.findByEmail(request.email()).isPresent()) throw existing();
            throw duplicate;
        }
        boolean delivered=verification.send(request.email(), request.password());
        return new Result(true, !delivered, delivered ? "Se envió una verificación a tu correo electrónico"
                : "Tu cuenta quedó pendiente. No se pudo enviar el correo; solicita un reenvío de verificación.");
    }
    private static BusinessException existing() {
        return BusinessException.conflict("ACCOUNT_ACCESS_REQUIRED", "No se pudo crear una cuenta nueva con esos datos. Si ya tienes cuenta, inicia sesión para consultar o habilitar COMPRADOR; si tienes una verificación pendiente, solicita su reenvío.");
    }
}
