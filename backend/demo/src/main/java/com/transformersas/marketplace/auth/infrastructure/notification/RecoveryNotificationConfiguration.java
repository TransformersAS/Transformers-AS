package com.transformersas.marketplace.auth.infrastructure.notification;

import com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RecoveryNotificationConfiguration {
    /** Delivery intentionally disabled until a real provider implements the port. */
    @Bean
    @ConditionalOnMissingBean(PasswordRecoveryNotifier.class)
    PasswordRecoveryNotifier passwordRecoveryNotifier() {
        return (email, token) -> { };
    }
}
