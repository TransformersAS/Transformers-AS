package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.usecase.FindOwnOrders;
import com.transformersas.marketplace.orders.application.usecase.GetBuyerOrderDetailUseCase;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.users.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyerOrderHistoryAuthorizationTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderStatusHistoryRepository history = mock(OrderStatusHistoryRepository.class);
    private final GetBuyerOrderDetailUseCase useCase = new GetBuyerOrderDetailUseCase(new FindOwnOrders(orders), history);
    private final AccountPrincipal buyer = new AccountPrincipal(new UserAccount(5L, "buyer@example.test",
            "$2a$04$" + "a".repeat(53), AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));

    @Test void historyIsReadOnlyAfterAccountScopedOrderIsFound() {
        Order order = mock(Order.class);
        when(order.id()).thenReturn(7L);
        when(orders.findByIdAndAccountId(7L, 5L)).thenReturn(Optional.of(order));
        when(history.findByOrderId(7L)).thenReturn(List.of());
        assertThat(useCase.execute(7L, buyer).history()).isEmpty();
        var sequence = inOrder(orders, history);
        sequence.verify(orders).findByIdAndAccountId(7L, 5L);
        sequence.verify(history).findByOrderId(7L);
        verifyNoMoreInteractions(orders, history);
    }

    @Test void foreignOrMissingOrderNeverReadsHistory() {
        when(orders.findByIdAndAccountId(7L, 5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.execute(7L, buyer)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(history);
    }

    @Test void unauthenticatedRequestDoesNotAccessEitherRepository() {
        assertThatThrownBy(() -> useCase.execute(7L, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(orders, history);
    }
}
