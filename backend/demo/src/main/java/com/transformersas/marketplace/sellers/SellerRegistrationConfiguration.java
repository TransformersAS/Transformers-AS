package com.transformersas.marketplace.sellers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SellerRegistrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SellerRegistrationConfiguration.class);

    /**
     * Todavía no hay un proveedor de correo real: por defecto el aviso no se entrega (y el token no se registra en
     * ningún sitio). Para probar el flujo en un entorno de desarrollo se puede activar sellers.verification.log-token,
     * que escribe el token en el registro del servidor, como si fuera el correo. Un proveedor real solo tiene que
     * definir su propio EmailVerificationNotifier.
     */
    @Bean
    @ConditionalOnMissingBean(EmailVerificationNotifier.class)
    EmailVerificationNotifier emailVerificationNotifier(
            @Value("${sellers.verification.log-token:false}") boolean logToken) {
        return (email, token) -> {
            if (logToken) {
                log.warn("[SOLO DESARROLLO] Correo de verificación para {}: token {}", email, token);
            }
        };
    }
}
