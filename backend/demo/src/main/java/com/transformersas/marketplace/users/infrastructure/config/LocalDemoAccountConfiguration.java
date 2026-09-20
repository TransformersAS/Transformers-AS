package com.transformersas.marketplace.users.infrastructure.config;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.util.Set;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalDemoAccountConfiguration {
    @Bean
    ApplicationRunner provisionLocalDemoAccount(UserAccountRepository accounts, PasswordEncoder encoder) {
        return args -> {
            String email = "demo@marketplace.local";
            if (accounts.findByEmail(email).isEmpty()) {
                accounts.save(new UserAccount(null, email, encoder.encode("MarketplaceDemo123!"),
                        AccountStatus.ACTIVA, Set.of(Role.COMPRADOR, Role.VENDEDOR)));
            }
        };
    }
}
