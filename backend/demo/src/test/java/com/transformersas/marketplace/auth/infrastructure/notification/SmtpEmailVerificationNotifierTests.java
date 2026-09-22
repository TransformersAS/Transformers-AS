package com.transformersas.marketplace.auth.infrastructure.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SmtpEmailVerificationNotifierTests {
    @Test
    void absentSmtpConfigurationFailsInsteadOfPretendingToDeliver() {
        var beans = new DefaultListableBeanFactory();
        var notifier = new SmtpEmailVerificationNotifier(beans.getBeanProvider(JavaMailSender.class), "verify@example.com");
        assertThatThrownBy(() -> notifier.notifyVerification("recipient@example.com", "secret-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Verification mail is not configured");
    }

    @Test
    void absentSenderAddressFailsBeforeContactingSmtp() {
        var beans = new DefaultListableBeanFactory();
        var sender = mock(JavaMailSender.class);
        beans.registerSingleton("sender", sender);
        var notifier = new SmtpEmailVerificationNotifier(beans.getBeanProvider(JavaMailSender.class), "");
        assertThatThrownBy(() -> notifier.notifyVerification("recipient@example.com", "secret-token"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sender);
    }
}
