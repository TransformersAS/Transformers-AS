package com.transformersas.marketplace.users.infrastructure.config;

import com.transformersas.marketplace.shared.PasswordEncodingConfiguration;
import com.transformersas.marketplace.stores.application.usecase.AssignStoreOwnerUseCase;
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
        var assignStoreOwner = mock(AssignStoreOwnerUseCase.class);
        when(accounts.findByEmail("demo@marketplace.local")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(call -> {
            UserAccount saved = call.getArgument(0);
            return new UserAccount(9L, saved.email(), saved.passwordHash(), saved.status(), saved.roles());
        });
        context.withPropertyValues("spring.profiles.active=local")
                .withUserConfiguration(PasswordEncodingConfiguration.class)
                .withBean(UserAccountRepository.class, () -> accounts)
                .withBean(AssignStoreOwnerUseCase.class, () -> assignStoreOwner)
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
                    verify(assignStoreOwner).execute(1L, 9L);
                    when(accounts.findByEmail(account.email())).thenReturn(Optional.of(new UserAccount(9L,
                            account.email(), account.passwordHash(), account.status(), account.roles())));
                    runner.run(new DefaultApplicationArguments());
                    verify(accounts, times(1)).save(any());
                    verify(assignStoreOwner, times(2)).execute(1L, 9L);
                });
    }

    @Test
    void existingSellerDemoIsAssignedTheMainStoreWithoutBeingRecreated() {
        var accounts = mock(UserAccountRepository.class);
        var assignStoreOwner = mock(AssignStoreOwnerUseCase.class);
        var existing = new UserAccount(4L, "demo@marketplace.local", "$2a$12$" + "a".repeat(53),
                AccountStatus.ACTIVA, Set.of(Role.COMPRADOR, Role.VENDEDOR));
        when(accounts.findByEmail(existing.email())).thenReturn(Optional.of(existing));
        context.withPropertyValues("spring.profiles.active=local")
                .withBean(UserAccountRepository.class, () -> accounts)
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(AssignStoreOwnerUseCase.class, () -> assignStoreOwner)
                .run(app -> app.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments()));
        verify(accounts, never()).save(any());
        verify(assignStoreOwner).execute(1L, 4L);
    }

    @Test
    void existingAccountIsNotReactivatedOrGivenNewRolesOrPassword() {
        var accounts = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var assignStoreOwner = mock(AssignStoreOwnerUseCase.class);
        var existing = new UserAccount(1L, "demo@marketplace.local", "$2a$12$" + "a".repeat(53),
                AccountStatus.INACTIVA, Set.of(Role.COMPRADOR));
        when(accounts.findByEmail(existing.email())).thenReturn(Optional.of(existing));
        context.withPropertyValues("spring.profiles.active=local")
                .withBean(UserAccountRepository.class, () -> accounts)
                .withBean(PasswordEncoder.class, () -> encoder)
                .withBean(AssignStoreOwnerUseCase.class, () -> assignStoreOwner)
                .run(app -> app.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments()));
        verify(accounts, never()).save(any());
        verifyNoInteractions(encoder);
        verifyNoInteractions(assignStoreOwner);
    }
}
