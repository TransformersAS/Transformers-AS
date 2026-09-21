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

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Registro de vendedores (CU-12). Una persona habilita el rol VENDEDOR de una de dos maneras: si ya tiene cuenta usa la
 * misma (enable), y si no, crea una nueva con sus datos (register). En ambos casos registra una tienda con nombre único
 * y acepta las condiciones. El rol VENDEDOR se concede cuando el registro está confirmado; una cuenta anterior a este
 * caso de uso ya cuenta como confirmada.
 *
 * <p>Primera entrega: la confirmación consiste en escribir el nombre de la tienda registrada. Sirve para asegurar el
 * paso, pero NO comprueba que la persona tenga acceso al buzón del correo; enviar un código secreto por correo queda
 * para una entrega posterior.
 */
@Service
public class SellerRegistrationService {

    /** Resultado del registro: qué tienda quedó reservada y en qué punto está el rol de vendedor. */
    public record Registration(Long storeId, String storeName, boolean emailVerificationRequired,
                               boolean sellerRoleActive) {
    }

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final CreateStoreUseCase createStore;
    private final StoreRepository stores;
    private final SellerRegistrationRepository registrations;

    public SellerRegistrationService(UserAccountRepository accounts, PasswordEncoder encoder,
                                     CreateStoreUseCase createStore, StoreRepository stores,
                                     SellerRegistrationRepository registrations) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.createStore = createStore;
        this.stores = stores;
        this.registrations = registrations;
    }

    /**
     * Un visitante sin cuenta crea una nueva junto con su tienda. La cuenta nace con el rol COMPRADOR y el registro sin
     * confirmar; el rol VENDEDOR llega al confirmarlo. Todo va en una transacción: si el nombre de la tienda ya existe
     * no queda ni la cuenta.
     */
    @Transactional
    public Registration register(String email, String password, String storeName) {
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

    /**
     * Confirma el registro: el correo y el nombre de la tienda deben corresponder a una cuenta con esa tienda. Se
     * responde igual cuando el correo no existe, para no revelar qué correos tienen cuenta. Confirmar dos veces no
     * cambia nada.
     */
    @Transactional
    public void verify(String email, String storeName) {
        Optional<UserAccount> account = accounts.findByEmail(email);
        Optional<Store> store = account.flatMap(found -> stores.findByOwnerAccountId(found.id()));
        if (store.isEmpty() || !sameName(store.get().profile().name(), storeName)) {
            throw BusinessException.invalid("INVALID_VERIFICATION",
                    "No coincide con el registro: revisa el correo y el nombre de tu tienda");
        }
        registrations.markVerified(account.get().id());
        grantSellerRole(account.get());
    }

    // ---------- Métodos auxiliares ----------

    /** Concede el rol VENDEDOR si la cuenta todavía no lo tiene. */
    private void grantSellerRole(UserAccount account) {
        if (account.roles().contains(Role.VENDEDOR)) {
            return;
        }
        Set<Role> roles = new HashSet<>(account.roles());
        roles.add(Role.VENDEDOR);
        accounts.save(new UserAccount(account.id(), account.email(), account.passwordHash(), account.status(), roles));
    }

    /** Compara nombres sin distinguir mayúsculas, tildes ni espacios sobrantes, igual que la unicidad de las tiendas. */
    private static boolean sameName(String registered, String typed) {
        return normalize(registered).equals(normalize(typed));
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
