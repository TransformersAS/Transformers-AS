package com.transformersas.marketplace.users.infrastructure.config;

import com.transformersas.marketplace.stores.application.usecase.AssignStoreOwnerUseCase;
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
    /** "Tienda principal" (V13): la que opera el vendedor demo. */
    static final long DEMO_STORE_ID = 1L;

    @Bean
    ApplicationRunner provisionLocalDemoAccount(UserAccountRepository accounts, PasswordEncoder encoder,
                                                AssignStoreOwnerUseCase assignStoreOwner) {
        return args -> {
            String email = "demo@marketplace.local";
            UserAccount demo = accounts.findByEmail(email).orElseGet(() -> accounts.save(new UserAccount(null, email,
                    encoder.encode("MarketplaceDemo123!"), AccountStatus.ACTIVA, Set.of(Role.COMPRADOR, Role.VENDEDOR))));
            if (demo.roles().contains(Role.VENDEDOR)) {
                // No reemplaza a una dueña existente: en reinicios posteriores no hace nada.
                assignStoreOwner.execute(DEMO_STORE_ID, demo.id());
            }
        };
    }
}
