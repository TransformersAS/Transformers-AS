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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Registro de vendedores (CU-12). Una persona habilita el rol VENDEDOR de una de dos maneras: si ya tiene cuenta usa la
 * misma (enable), y si no, crea una nueva con sus datos (register). En ambos casos registra una tienda con nombre único
 * y acepta las condiciones. El correo se verifica cuando hace falta: el rol VENDEDOR solo se concede cuando el correo
 * está verificado; una cuenta anterior a este caso de uso ya cuenta como verificada.
 */
@Service
public class SellerRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SellerRegistrationService.class);

    /** Resultado del registro: qué tienda quedó reservada y en qué punto está el rol de vendedor. */
    public record Registration(Long storeId, String storeName, boolean emailVerificationRequired,
                               boolean sellerRoleActive) {
    }

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final CreateStoreUseCase createStore;
    private final StoreRepository stores;
    private final EmailVerificationRepository verifications;
    private final EmailVerificationNotifier notifier;
    private final SecureRandom random = new SecureRandom();

    public SellerRegistrationService(UserAccountRepository accounts, PasswordEncoder encoder,
                                     CreateStoreUseCase createStore, StoreRepository stores,
                                     EmailVerificationRepository verifications, EmailVerificationNotifier notifier) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.createStore = createStore;
        this.stores = stores;
        this.verifications = verifications;
        this.notifier = notifier;
    }

    /**
     * Un visitante sin cuenta crea una nueva junto con su tienda. La cuenta nace con el rol COMPRADOR y su correo sin
     * verificar; el rol VENDEDOR llega cuando confirma el correo. Todo va en una transacción: si el nombre de la tienda
     * ya existe no queda ni la cuenta.
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
        verifications.recordTermsAcceptance(account.id(), SellerTerms.VERSION);
        sendVerification(account);
        return new Registration(store.id(), store.profile().name(), true, false);
    }

    /**
     * Una persona con cuenta habilita el rol VENDEDOR con la misma cuenta y credenciales. Si su correo ya está verificado
     * el rol se concede de inmediato; si no, queda pendiente hasta que confirme el correo.
     */
    @Transactional
    public Registration enable(Long accountId, String storeName) {
        UserAccount account = findAccount(accountId);
        if (account.roles().contains(Role.VENDEDOR)) {
            throw BusinessException.conflict("SELLER_ALREADY_ENABLED", "Tu cuenta ya tiene el rol de vendedor");
        }
        Store store = createStore.execute(accountId, storeName);
        verifications.recordTermsAcceptance(accountId, SellerTerms.VERSION);
        boolean verified = verifications.isVerified(accountId);
        if (verified) {
            grantSellerRole(account);
        } else {
            sendVerification(account);
        }
        return new Registration(store.id(), store.profile().name(), !verified, verified);
    }

    /** Confirma el correo con el token recibido y, si la cuenta tiene una tienda reservada, le concede el rol VENDEDOR. */
    @Transactional
    public void verifyEmail(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw invalidToken();
        }
        String hash = hash(token);
        Long accountId = verifications.findAccountId(hash).orElseThrow(SellerRegistrationService::invalidToken);
        verifications.lockAccount(accountId);
        if (!verifications.consume(hash)) {
            throw invalidToken();
        }
        verifications.markVerified(accountId);
        if (stores.findByOwnerAccountId(accountId).isPresent()) {
            grantSellerRole(findAccount(accountId));
        }
    }

    /** Envía un token nuevo (el anterior deja de servir), por ejemplo cuando el primero venció. */
    @Transactional
    public void resendVerification(Long accountId) {
        if (verifications.isVerified(accountId)) {
            throw BusinessException.conflict("EMAIL_ALREADY_VERIFIED", "Tu correo ya está verificado");
        }
        sendVerification(findAccount(accountId));
    }

    // ---------- Métodos auxiliares ----------

    private UserAccount findAccount(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> BusinessException.unauthenticated("ACCOUNT_NOT_FOUND", "La cuenta no existe"));
    }

    private void grantSellerRole(UserAccount account) {
        Set<Role> roles = new HashSet<>(account.roles());
        roles.add(Role.VENDEDOR);
        accounts.save(new UserAccount(account.id(), account.email(), account.passwordHash(), account.status(), roles));
    }

    /** Guarda el token (solo su hash) y entrega el correo cuando la transacción ya se confirmó. */
    private void sendVerification(UserAccount account) {
        verifications.lockAccount(account.id());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        verifications.replaceToken(account.id(), hash(token));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Un fallo al entregar el correo no debe deshacer el registro.
                try {
                    notifier.notifyVerification(account.email(), token);
                } catch (RuntimeException failure) {
                    log.warn("No se pudo entregar el correo de verificación");
                }
            }
        });
    }

    private static BusinessException invalidToken() {
        return BusinessException.invalid("INVALID_VERIFICATION_TOKEN", "Token inválido o expirado");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
