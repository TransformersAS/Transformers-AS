package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.application.port.EmailVerificationNotifier;
import com.transformersas.marketplace.auth.domain.model.EmailVerified;
import com.transformersas.marketplace.auth.domain.repository.EmailVerificationTokenRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class VerifyAccountEmail {
    private final UserAccountRepository accounts;
    private final EmailVerificationTokenRepository tokens;
    private final EmailVerificationNotifier notifier;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();
    private final String missingAccountHash;

    public VerifyAccountEmail(UserAccountRepository accounts, EmailVerificationTokenRepository tokens,
            EmailVerificationNotifier notifier, PasswordEncoder encoder, PlatformTransactionManager transactions,
            ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.notifier = notifier;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(transactions);
        // The account row serializes issuance/consumption. READ_COMMITTED avoids range/gap locks
        // on the token index when different accounts receive their first token concurrently.
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.events = events;
        this.missingAccountHash = encoder.encode(Base64.getEncoder().encodeToString(random.generateSeed(32)));
    }

    /** Requires correct credentials before revealing verification/delivery status; never authenticates a session. */
    public boolean send(String email, String password) {
        if (email == null || email.isBlank() || email.length() > 254 || password == null
                || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        var found = accounts.findByEmail(email);
        boolean matches = encoder.matches(password, found.map(a -> a.passwordHash()).orElse(missingAccountHash));
        if (!matches || found.isEmpty() || found.get().status() != AccountStatus.ACTIVA) throw invalidCredentials();
        var account = found.get();
        // Commit the hash before contacting SMTP. Delivery failure keeps the account pending and can be retried.
        String token = transaction.execute(status -> {
            tokens.lockAccount(account.id());
            var current = accounts.findById(account.id()).orElseThrow(VerifyAccountEmail::invalidCredentials);
            if (current.status() != AccountStatus.ACTIVA || !current.passwordHash().equals(account.passwordHash())) {
                throw invalidCredentials();
            }
            if (accounts.isEmailVerified(account.id())) return null;
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            tokens.replace(account.id(), hash(secret));
            return secret;
        });
        if (token == null) return true;
        try {
            notifier.notifyVerification(account.email(), token);
            return true;
        } catch (RuntimeException exception) {
            // SMTP exceptions may contain recipients, credentials or the message: do not log them.
            org.slf4j.LoggerFactory.getLogger(VerifyAccountEmail.class).warn("Email verification delivery failed");
            return false;
        }
    }

    @Transactional
    public void confirm(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        String hash = hash(token);
        Long id = tokens.findAccountId(hash).orElseThrow(VerifyAccountEmail::invalidToken);
        tokens.lockAccount(id);
        var account = accounts.findById(id).orElseThrow(VerifyAccountEmail::invalidToken);
        if (account.status() != AccountStatus.ACTIVA || accounts.isEmailVerified(id) || !tokens.consume(hash)) {
            throw invalidToken();
        }
        accounts.markEmailVerified(id);
        events.publishEvent(new EmailVerified(id));
    }

    private static BusinessException invalidCredentials() {
        return BusinessException.unauthenticated("INVALID_CREDENTIALS", "Credenciales inválidas");
    }

    private static BusinessException invalidToken() {
        return BusinessException.invalid("INVALID_VERIFICATION_TOKEN", "Código de verificación inválido o vencido");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
