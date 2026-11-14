package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

@Service
public class ChangeAccountPassword {
    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final ManageAccountSessions sessions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ChangeAccountPassword(UserAccountRepository accounts, PasswordEncoder encoder, ManageAccountSessions sessions) {
        this(accounts,encoder,sessions,null);
    }

    /** Conserva el constructor de pruebas; en ejecución comparte el bloqueo de identidad de CU09. */
    @org.springframework.beans.factory.annotation.Autowired
    public ChangeAccountPassword(UserAccountRepository accounts, PasswordEncoder encoder, ManageAccountSessions sessions,
                                 org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.accounts = accounts;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    @Transactional
    public void execute(AccountPrincipal principal, String currentPassword, String newPassword, String currentSessionId) {
        if (currentPassword == null || currentPassword.isBlank()
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contraseña actual inválida");
        }
        PasswordPolicy.validate(newPassword);
        // Evita reescribir un correo o estado anteriores durante cierre/cambio de correo concurrentes.
        if (jdbc != null) jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",principal.accountId());
        var account = accounts.findById(principal.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
        if (account.status() != com.transformersas.marketplace.users.domain.model.AccountStatus.ACTIVA
                || !account.email().equals(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La cuenta cambió; inicia sesión nuevamente");
        }
        if (!encoder.matches(currentPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contraseña actual incorrecta");
        }
        if (encoder.matches(newPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva contraseña debe ser diferente");
        }
        accounts.save(new UserAccount(account.id(), account.email(), encoder.encode(newPassword),
                account.status(), account.roles()));
        sessions.revokeOthers(principal, currentSessionId);
    }
}
