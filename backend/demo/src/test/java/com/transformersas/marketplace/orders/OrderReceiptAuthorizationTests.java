package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.usecase.*;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.port.OrderReceiptPdfGenerator;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.users.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderReceiptAuthorizationTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderReceiptPdfGenerator pdf = mock(OrderReceiptPdfGenerator.class);
    private final GenerateBuyerOrderReceiptUseCase useCase = new GenerateBuyerOrderReceiptUseCase(new FindOwnOrders(orders), pdf);
    private final AccountPrincipal principal = new AccountPrincipal(new UserAccount(5L, "buyer@example.test",
            "$2a$04$" + "a".repeat(53), AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));

    @Test void generatesOnlyAfterAccountScopedAuthorization() {
        Order order = mock(Order.class);
        when(orders.findByIdAndAccountId(7L, 5L)).thenReturn(Optional.of(order));
        byte[] bytes = {1, 2, 3};
        when(pdf.generate(eq(order), any())).thenReturn(bytes);
        assertThat(useCase.execute(7L, principal)).isSameAs(bytes);
        var sequence = inOrder(orders, pdf);
        sequence.verify(orders).findByIdAndAccountId(7L, 5L);
        sequence.verify(pdf).generate(eq(order), any());
        verifyNoMoreInteractions(orders, pdf);
    }
    @Test void foreignOrMissingOrderDoesNotGenerateAnything() {
        when(orders.findByIdAndAccountId(7L, 5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.execute(7L, principal)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(pdf);
    }
    @Test void missingSessionDoesNotReadOrGenerateAnything() {
        assertThatThrownBy(() -> useCase.execute(7L, null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(orders, pdf);
    }
}
