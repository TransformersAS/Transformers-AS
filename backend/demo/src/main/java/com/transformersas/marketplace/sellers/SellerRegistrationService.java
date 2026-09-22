package com.transformersas.marketplace.sellers;

import com.transformersas.marketplace.auth.application.usecase.PasswordPolicy;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.CreateStoreUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.transformersas.marketplace.auth.application.usecase.VerifyAccountEmail;
import com.transformersas.marketplace.auth.domain.model.EmailVerified;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashSet;
import java.util.Set;

/**
 * Registro de vendedores (CU-12). Una persona habilita el rol VENDEDOR de una de dos maneras: si ya tiene cuenta usa la
 * misma (enable), y si no, crea una nueva con sus datos (register). En ambos casos registra una tienda con nombre único
 * y acepta las condiciones. El rol VENDEDOR se concede cuando el registro está confirmado; una cuenta anterior a este
 * caso de uso ya cuenta como confirmada.
 *
 * El correo se confirma exclusivamente mediante el token enviado al buzón (CU-08).
 */
@Service
public class SellerRegistrationService {

    /** Resultado del registro: qué tienda quedó reservada y en qué punto está el rol de vendedor. */
    public record Registration(Long storeId, String storeName, boolean emailVerificationRequired,
                               boolean sellerRoleActive, boolean verificationDeliveryFailed) {
        public Registration(Long storeId, String storeName, boolean emailVerificationRequired, boolean sellerRoleActive) {
            this(storeId, storeName, emailVerificationRequired, sellerRoleActive, false);
        }
    }

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final CreateStoreUseCase createStore;
    private final StoreRepository stores;
    private final SellerRegistrationRepository registrations;
    private final VerifyAccountEmail verification;
    private final TransactionTemplate transaction;

    public SellerRegistrationService(UserAccountRepository accounts, PasswordEncoder encoder,
                                     CreateStoreUseCase createStore, StoreRepository stores,
                                     SellerRegistrationRepository registrations, VerifyAccountEmail verification,
                                     PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.createStore = createStore;
        this.stores = stores;
        this.registrations = registrations;
        this.verification = verification;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Un visitante sin cuenta crea una nueva junto con su tienda. La cuenta nace con el rol COMPRADOR y el registro sin
     * confirmar; el rol VENDEDOR llega al confirmarlo. Todo va en una transacción: si el nombre de la tienda ya existe
     * no queda ni la cuenta.
     */
    public Registration register(String email, String password, String storeName) {
        Registration result = transaction.execute(status -> registerAccount(email, password, storeName));
        // The account/store transaction is committed before SMTP. A failed delivery never loses the registration.
        boolean delivered = verification.send(email, password);
        return new Registration(result.storeId(), result.storeName(), true, false, !delivered);
    }

    private Registration registerAccount(String email, String password, String storeName) {
        if (accounts.findByEmail(email).isPresent()) {
            throw BusinessException.conflict("EMAIL_ALREADY_REGISTERED",
                    "Ya existe una cuenta con ese correo: inicia sesión y habilita el rol de vendedor");
        }
        PasswordPolicy.validate(password);
        UserAccount account = accounts.save(new UserAccount(null, email, encoder.encode(password),
                AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
        Store store = createStore.execute(account.id(), storeName);
        registrations.recordTermsAcceptance(account.id(), SellerTerms.VERSION);
        return new Registration(store.id(), store.profile().name(), true, false);
    }

    /**
     * Una persona con cuenta habilita el rol VENDEDOR con la misma cuenta y credenciales. Si su registro ya está
     * confirmado el rol se concede de inmediato; si no, queda pendiente hasta que lo confirme.
     */
    @Transactional
    public Registration enable(Long accountId, String storeName) {
        UserAccount account = accounts.findById(accountId)
                .orElseThrow(() -> BusinessException.unauthenticated("ACCOUNT_NOT_FOUND", "La cuenta no existe"));
        if (account.roles().contains(Role.VENDEDOR)) {
            throw BusinessException.conflict("SELLER_ALREADY_ENABLED", "Tu cuenta ya tiene el rol de vendedor");
        }
        Store store = createStore.execute(accountId, storeName);
        registrations.recordTermsAcceptance(accountId, SellerTerms.VERSION);
        boolean verified = registrations.isVerified(accountId);
        if (verified) {
            grantSellerRole(account);
        }
        return new Registration(store.id(), store.profile().name(), !verified, verified);
    }

    /** Joins the token confirmation transaction; no public name-based verification remains. */
    @EventListener
    public void emailVerified(EmailVerified event) {
        if (stores.findByOwnerAccountId(event.accountId()).isPresent()
                && registrations.hasTermsAcceptance(event.accountId())) {
            accounts.findById(event.accountId()).ifPresent(this::grantSellerRole);
        }
    }

    /** Concede el rol VENDEDOR si la cuenta todavía no lo tiene. */
    private void grantSellerRole(UserAccount account) {
        if (account.roles().contains(Role.VENDEDOR)) {
            return;
        }
        Set<Role> roles = new HashSet<>(account.roles());
        roles.add(Role.VENDEDOR);
        accounts.save(new UserAccount(account.id(), account.email(), account.passwordHash(), account.status(), roles));
    }

}
