package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RecoveryNotificationConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RecoveryNotificationConfiguration.class);
    @Test
    void disabledDeliveryDoesNotLogRecoverySecrets(CapturedOutput output) {
        context.run(app -> {
            assertThat(app).hasSingleBean(PasswordRecoveryNotifier.class);
            app.getBean(PasswordRecoveryNotifier.class).notifyRecovery("private-recipient@example.com", "secret-recovery-token");
        });
        assertThat(output.getAll()).doesNotContain("secret-recovery-token", "private-recipient@example.com");
    }
    @Test
    void configuredDeliveryPortReplacesTheDisabledFallback() {
        var notifier = mock(PasswordRecoveryNotifier.class);
        context.withBean(PasswordRecoveryNotifier.class, () -> notifier).run(app -> {
            assertThat(app).hasSingleBean(PasswordRecoveryNotifier.class);
            assertThat(app.getBean(PasswordRecoveryNotifier.class)).isSameAs(notifier);
            app.getBean(PasswordRecoveryNotifier.class).notifyRecovery("buyer@example.com", "token");
        });
        verify(notifier).notifyRecovery("buyer@example.com", "token");
    }
}
