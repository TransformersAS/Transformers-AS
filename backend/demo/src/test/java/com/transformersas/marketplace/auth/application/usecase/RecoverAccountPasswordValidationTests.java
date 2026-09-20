package com.transformersas.marketplace.auth.application.usecase;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import com.transformersas.marketplace.auth.infrastructure.persistence.repository.RecoveryTokenRepository;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoverAccountPasswordValidationTests {
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final RecoveryTokenRepository tokens = mock(RecoveryTokenRepository.class);
    private final PasswordRecoveryNotifier notifier = mock(PasswordRecoveryNotifier.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ManageAccountSessions sessions = mock(ManageAccountSessions.class);
    private final RecoverAccountPassword recovery = new RecoverAccountPassword(accounts, tokens, notifier, encoder, sessions);

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  "})
    void ignoresMissingEmailWithoutLookingUpOrNotifyingAccounts(String email) {
        recovery.request(email);
        verifyNoInteractions(accounts, tokens, notifier, encoder, sessions);
    }
    @Test
    void ignoresEmailExceedingLengthLimit() {
        recovery.request("a".repeat(243) + "@example.com");
        verifyNoInteractions(accounts, tokens, notifier, encoder, sessions);
    }
    @Test
    void acceptsEmailAtLengthBoundaryForLookup() {
        String email = "a".repeat(242) + "@example.com";
        recovery.request(email);
        verify(accounts).findByEmail(email);
        verifyNoInteractions(tokens, notifier, encoder, sessions);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"bad-token", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"})
    void rejectsMalformedTokensBeforePersistenceOrSessionChanges(String token) {
        assertThatThrownBy(() -> recovery.confirm(token, "NewPassword!123"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getReason()).isEqualTo("Token inválido o expirado");
                });
        verifyNoInteractions(accounts, tokens, notifier, encoder, sessions);
    }
}
