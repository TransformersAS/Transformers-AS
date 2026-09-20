package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import com.transformersas.marketplace.auth.infrastructure.persistence.repository.RecoveryTokenRepository;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecoverAccountPassword {
    private final UserAccountRepository accounts;
    private final RecoveryTokenRepository tokens;
    private final PasswordRecoveryNotifier notifier;
    private final PasswordEncoder encoder;
    private final ManageAccountSessions sessions;
    private final SecureRandom random = new SecureRandom();

    public RecoverAccountPassword(UserAccountRepository accounts, RecoveryTokenRepository tokens,
            PasswordRecoveryNotifier notifier, PasswordEncoder encoder, ManageAccountSessions sessions) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.notifier = notifier;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    @Transactional
    public void request(String email) {
        if (email == null || email.isBlank() || email.length() > 254) return;
        var found = accounts.findByEmail(email);
        if (found.isEmpty() || found.get().status() != AccountStatus.ACTIVA) return;
        var account = found.get();
        tokens.lockAccount(account.id());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.replace(account.id(), hash(token));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Delivery failures must not reveal whether the account exists through the HTTP response.
                try { notifier.notifyRecovery(account.email(), token); }
                catch (RuntimeException exception) {
                    org.slf4j.LoggerFactory.getLogger(RecoverAccountPassword.class)
                            .warn("Password recovery notification could not be delivered");
                }
            }
        });
    }

    @Transactional
    public void confirm(String token, String newPassword) {
        PasswordPolicy.validate(newPassword);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        String hash = hash(token);
        Long accountId = tokens.findAccountId(hash).orElseThrow(RecoverAccountPassword::invalidToken);
        tokens.lockAccount(accountId);
        var account = accounts.findById(accountId).orElseThrow(RecoverAccountPassword::invalidToken);
        if (account.status() != AccountStatus.ACTIVA || !tokens.consume(hash)) throw invalidToken();
        if (encoder.matches(newPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser diferente");
        }
        accounts.save(new UserAccount(account.id(), account.email(), encoder.encode(newPassword), account.status(), account.roles()));
        var principal = new AccountPrincipal(account);
        principal.eraseCredentials();
        sessions.revokeAll(principal);
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token inválido o expirado");
    }
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
