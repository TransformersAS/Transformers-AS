package com.transformersas.marketplace.users.infrastructure.config;

import com.transformersas.marketplace.shared.PasswordEncodingConfiguration;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalDemoAccountConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalDemoAccountConfiguration.class);

    @Test
    void defaultProfileDoesNotProvisionAccounts() {
        context.run(app -> assertThat(app).doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void productionProfileDoesNotProvisionAccounts() {
        context.withPropertyValues("spring.profiles.active=production")
                .run(app -> assertThat(app).doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void localProfileCreatesActiveDemoWithBothRolesAndBcryptOnlyOnce() {
        var accounts = mock(UserAccountRepository.class);
        when(accounts.findByEmail("demo@marketplace.local")).thenReturn(Optional.empty());
        context.withPropertyValues("spring.profiles.active=local")
                .withUserConfiguration(PasswordEncodingConfiguration.class)
                .withBean(UserAccountRepository.class, () -> accounts)
                .run(app -> {
                    var runner = app.getBean(ApplicationRunner.class);
                    runner.run(new DefaultApplicationArguments());
                    var captured = ArgumentCaptor.forClass(UserAccount.class);
                    verify(accounts).save(captured.capture());
                    var account = captured.getValue();
                    assertThat(account.email()).isEqualTo("demo@marketplace.local");
                    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVA);
                    assertThat(account.roles()).containsExactlyInAnyOrder(Role.COMPRADOR, Role.VENDEDOR);
                    assertThat(account.passwordHash()).startsWith("$2").isNotEqualTo("MarketplaceDemo123!");
                    assertThat(app.getBean(PasswordEncoder.class)
                            .matches("MarketplaceDemo123!", account.passwordHash())).isTrue();
                    when(accounts.findByEmail(account.email())).thenReturn(Optional.of(account));
                    runner.run(new DefaultApplicationArguments());
                    verify(accounts, times(1)).save(any());
                });
    }

    @Test
    void existingAccountIsNotReactivatedOrGivenNewRolesOrPassword() {
        var accounts = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var existing = new UserAccount(1L, "demo@marketplace.local", "$2a$12$" + "a".repeat(53),
                AccountStatus.INACTIVA, Set.of(Role.COMPRADOR));
        when(accounts.findByEmail(existing.email())).thenReturn(Optional.of(existing));
        context.withPropertyValues("spring.profiles.active=local")
                .withBean(UserAccountRepository.class, () -> accounts)
                .withBean(PasswordEncoder.class, () -> encoder)
                .run(app -> app.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments()));
        verify(accounts, never()).save(any());
        verifyNoInteractions(encoder);
    }
}
