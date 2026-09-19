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

    public ChangeAccountPassword(UserAccountRepository accounts, PasswordEncoder encoder, ManageAccountSessions sessions) {
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
        if (newPassword == null || newPassword.isBlank()
                || newPassword.codePointCount(0, newPassword.length()) < 12
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nueva contraseña requiere al menos 12 caracteres y un máximo de 72 bytes UTF-8");
        }
        var account = accounts.findById(principal.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
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
