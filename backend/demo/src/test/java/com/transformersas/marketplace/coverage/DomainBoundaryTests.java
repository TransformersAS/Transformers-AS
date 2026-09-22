package com.transformersas.marketplace.coverage;

import com.transformersas.marketplace.auth.application.usecase.*;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.*;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.application.usecase.*;
import com.transformersas.marketplace.orders.infrastructure.persistence.repository.*;
import com.transformersas.marketplace.payments.infrastructure.web.controller.PaymentController;
import com.transformersas.marketplace.payments.application.usecase.ProcessPaymentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainBoundaryTests {
    private static final String HASH = "$2a$04$" + "a".repeat(53);
    private static UserAccount account(Long id) {
        return new UserAccount(id, "buyer@example.com", HASH, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR));
    }
    static Stream<String> invalidEmails() { return Stream.of("", "  ", "a".repeat(243) + "@example.com"); }
    @ParameterizedTest @MethodSource("invalidEmails")
    void accountRejectsBlankOrOversizedNormalizedEmails(String email) {
        assertThatThrownBy(() -> new UserAccount(1L, email, HASH, AccountStatus.ACTIVA, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Email vacío o demasiado largo");
    }
    @Test
    void accountRequiresPasswordHashAndAcceptsMaximumEmailLength() {
        assertThatThrownBy(() -> new UserAccount(1L, "buyer@example.com", null, AccountStatus.ACTIVA, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Se requiere un hash BCrypt");
        String email = "a".repeat(242) + "@example.com";
        assertThat(new UserAccount(1L, " " + email.toUpperCase(Locale.ROOT) + " ", HASH, AccountStatus.ACTIVA, Set.of()).email()).isEqualTo(email);
    }
    @Test
    void orderRequiresOwnerOnlyForNewOrders() {
        assertThatThrownBy(() -> order(null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Un pedido nuevo requiere comprador");
        assertThat(order(1L, null).accountId()).isNull();
        assertThat(order(null, 1L).accountId()).isEqualTo(1L);
    }
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void orderRejectsInvalidOwner(long id) {
        assertThatThrownBy(() -> order(null, id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identificador de comprador inválido");
    }
    private static Order order(Long id, Long owner) {
        return new Order(id, owner, OrderStatus.CONFIRMED, OrderPaymentStatus.APPROVED, BigDecimal.TEN, 1L, 1L, "STANDARD",
                new DeliverySnapshot("Ana", "Calle 1", "Bogotá", "Bogotá", null, "1234567"), "tx", LocalDateTime.now(), List.of());
    }
    static Stream<AccountPrincipal> invalidPrincipals() {
        return Stream.of(null, new AccountPrincipal(account(null)), new AccountPrincipal(account(0L)), new AccountPrincipal(account(-1L)));
    }
    @ParameterizedTest @MethodSource("invalidPrincipals")
    void invalidIdentityCannotQueryOrCancelAnyOrder(AccountPrincipal principal) {
        var repository = mock(OrderRepository.class);
        var find = new FindOwnOrders(repository);
        var cancel = new RequestOrderCancellation(repository, mock(CancelOrderUseCase.class));
        unauthorized(() -> find.list(principal));
        unauthorized(() -> find.detail(1L, principal));
        unauthorized(() -> cancel.execute(1L, principal, CancellationReason.OTHER, "Motivo"));
        verifyNoInteractions(repository);
    }
    @Test
    void nullOwnerNeverReachesPersistenceQueries() {
        var persistence = mock(SpringDataOrderRepository.class);
        var repository = new OrderRepositoryAdapter(persistence);
        assertThat(repository.findByAccountId(null)).isEmpty();
        assertThat(repository.findByIdAndAccountId(1L, null)).isEmpty();
        assertThat(repository.requestCancellation(1L, null)).isFalse();
        assertThat(repository.existsByIdAndAccountId(1L, null)).isFalse();
        verifyNoInteractions(persistence);
    }
    @Test
    void paymentRejectsMissingIdentityBeforeProcessingAnything() {
        var payments = mock(ProcessPaymentUseCase.class);
        var controller = new PaymentController(payments);
        unauthorized(() -> controller.process(null, null));
        unauthorized(() -> controller.process(null, new AccountPrincipal(account(null))));
        verifyNoInteractions(payments);
    }
    @Test
    void roleSelectionRejectsMissingAndUnownedRolesWithoutChangingOriginalPrincipal() {
        var principal = new AccountPrincipal(account(1L));
        assertThatThrownBy(() -> principal.withActiveRole(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> principal.withActiveRole(Role.VENDEDOR)).isInstanceOf(IllegalArgumentException.class);
        assertThat(principal.activeRole()).isEqualTo(Role.COMPRADOR);
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_COMPRADOR");
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " "})
    void passwordChangeRejectsMissingCurrentPasswordWithoutSideEffects(String password) {
        var accounts = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var sessions = mock(ManageAccountSessions.class);
        var change = new ChangeAccountPassword(accounts, encoder, sessions);
        assertThatThrownBy(() -> change.execute(new AccountPrincipal(account(1L)), password, "NewPassword!123", "session"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(accounts, encoder, sessions);
    }
    @Test
    void passwordChangeRejectsOversizedUtf8PasswordAndDeletedAccount() {
        var accounts = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var sessions = mock(ManageAccountSessions.class);
        var change = new ChangeAccountPassword(accounts, encoder, sessions);
        var principal = new AccountPrincipal(account(1L));
        assertThatThrownBy(() -> change.execute(principal, "é".repeat(37), "NewPassword!123", "session"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(accounts, encoder, sessions);
        unauthorized(() -> change.execute(principal, "OldPassword!123", "NewPassword!123", "session"));
        verify(accounts).findById(1L);
        verifyNoInteractions(encoder, sessions);
    }
    private static void unauthorized(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
    }
}
