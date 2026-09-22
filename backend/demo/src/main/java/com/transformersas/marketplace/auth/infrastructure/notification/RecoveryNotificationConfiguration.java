package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RecoveryNotificationConfiguration {
    /** SMTP delivery is the default; alternative adapters may still implement the port. */
    @Bean
    @ConditionalOnMissingBean(PasswordRecoveryNotifier.class)
    PasswordRecoveryNotifier passwordRecoveryNotifier(ObjectProvider<JavaMailSender> senders,
            @Value("${app.password-recovery.from:}") String from) {
        return new SmtpPasswordRecoveryNotifier(senders, from);
    }
}
