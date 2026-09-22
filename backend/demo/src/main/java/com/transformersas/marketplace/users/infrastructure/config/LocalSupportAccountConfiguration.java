package com.transformersas.marketplace.users.infrastructure.config;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Solo desarrollo: el rol SOPORTE (CU-21) no se autoregistra por API a propósito, así que no hay forma de probarlo
 * contra un backend recién levantado sin crear la cuenta de antemano. Con SUPPORT_ACCOUNT_EMAIL y
 * SUPPORT_ACCOUNT_PASSWORD definidas, al arrancar crea esa cuenta con el rol SOPORTE si todavía no existe. Vacías
 * por defecto: no se toca nada. Una cuenta que ya exista no se modifica (ni contraseña ni roles).
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalSupportAccountConfiguration {

    @Bean
    ApplicationRunner provisionLocalSupportAccount(UserAccountRepository accounts, PasswordEncoder encoder,
            @Value("${support.account-email:}") String email, @Value("${support.account-password:}") String password) {
        return args -> {
            if (email.isBlank() || password.isBlank()) {
                return;
            }
            if (accounts.findByEmail(email).isEmpty()) {
                accounts.save(new UserAccount(null, email, encoder.encode(password), AccountStatus.ACTIVA,
                        Set.of(Role.SOPORTE)));
            }
        };
    }
}
