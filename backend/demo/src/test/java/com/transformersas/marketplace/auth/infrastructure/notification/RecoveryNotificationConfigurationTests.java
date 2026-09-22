package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RecoveryNotificationConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RecoveryNotificationConfiguration.class);

    @Test
    void missingSmtpFailsInsteadOfSilentlyDiscardingAndDoesNotLogSecrets(CapturedOutput output) {
        context.withPropertyValues("app.password-recovery.from=recovery@example.com").run(app -> {
            assertThat(app).hasSingleBean(PasswordRecoveryNotifier.class);
            assertThat(app.getBean(PasswordRecoveryNotifier.class)).isInstanceOf(SmtpPasswordRecoveryNotifier.class);
            assertThatThrownBy(() -> app.getBean(PasswordRecoveryNotifier.class)
                    .notifyRecovery("private-recipient@example.com", "secret-recovery-token"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Recovery mail is not configured");
        });
        assertThat(output.getAll()).doesNotContain("secret-recovery-token", "private-recipient@example.com");
    }

    @Test
    void missingRecoveryFromDoesNotUseVerificationSenderOrContactSmtp() {
        var sender = mock(JavaMailSender.class);
        context.withBean(JavaMailSender.class, () -> sender)
                .withPropertyValues("app.email-verification.from=verification@example.com").run(app -> {
            assertThatThrownBy(() -> app.getBean(PasswordRecoveryNotifier.class).notifyRecovery("buyer@example.com", "token"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Recovery mail is not configured");
        });
        verifyNoInteractions(sender);
    }

    @Test
    void defaultPortUsesTheConfiguredRecoverySenderAndSharedMailTransport() {
        var sender = mock(JavaMailSender.class);
        context.withBean(JavaMailSender.class, () -> sender)
                .withPropertyValues("app.password-recovery.from=recovery@example.com").run(app -> {
            app.getBean(PasswordRecoveryNotifier.class).notifyRecovery("buyer@example.com", "secret-recovery-token");
        });
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("recovery@example.com");
        assertThat(message.getValue().getTo()).containsExactly("buyer@example.com");
        assertThat(message.getValue().getText()).contains("secret-recovery-token", "15 minutos");
    }

    @Test
    void configuredDeliveryPortReplacesTheSmtpDefault() {
        var notifier = mock(PasswordRecoveryNotifier.class);
        context.withBean(PasswordRecoveryNotifier.class, () -> notifier).run(app -> {
            assertThat(app).hasSingleBean(PasswordRecoveryNotifier.class);
            assertThat(app.getBean(PasswordRecoveryNotifier.class)).isSameAs(notifier);
            app.getBean(PasswordRecoveryNotifier.class).notifyRecovery("buyer@example.com", "token");
        });
        verify(notifier).notifyRecovery("buyer@example.com", "token");
    }
}
