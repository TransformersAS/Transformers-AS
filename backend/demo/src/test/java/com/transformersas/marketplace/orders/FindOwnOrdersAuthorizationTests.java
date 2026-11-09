package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.port.BuyerRefundQuery;
import com.transformersas.marketplace.orders.application.usecase.FindOwnOrders;
import com.transformersas.marketplace.orders.application.usecase.GenerateBuyerOrderReceiptUseCase;
import com.transformersas.marketplace.orders.application.usecase.GetBuyerOrderDetailUseCase;
import com.transformersas.marketplace.orders.domain.port.OrderReceiptPdfGenerator;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FindOwnOrdersAuthorizationTests {
    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "ADMIN", "SOPORTE", "UNSELECTED", "MULTI_SELLER"})
    void deniesEveryBuyerReadBeforeAccessingDataWithoutActiveBuyerRole(String scenario) {
        var roles = scenario.equals("UNSELECTED") || scenario.equals("MULTI_SELLER")
                ? Set.of(Role.COMPRADOR, Role.VENDEDOR) : Set.of(Role.valueOf(scenario));
        var principal = new AccountPrincipal(new UserAccount(5L, "fixture@example.test", "$2a$04$" + "a".repeat(53),
                AccountStatus.ACTIVA, roles));
        if (scenario.equals("MULTI_SELLER")) principal = principal.withActiveRole(Role.VENDEDOR);
        final var actor = principal;
        var repository = mock(OrderRepository.class);
        var history = mock(OrderStatusHistoryRepository.class);
        var refunds = mock(BuyerRefundQuery.class);
        var pdf = mock(OrderReceiptPdfGenerator.class);
        var orders = new FindOwnOrders(repository);
        var detail = new GetBuyerOrderDetailUseCase(orders, history, refunds);
        var receipt = new GenerateBuyerOrderReceiptUseCase(orders, pdf);
        for (Runnable operation : new Runnable[]{
                () -> orders.list(actor), () -> orders.list(actor, null, null, null),
                () -> orders.detail(7L, actor), () -> detail.execute(7L, actor),
                () -> receipt.execute(7L, actor)}) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        }
        verifyNoInteractions(repository, history, refunds, pdf);
    }
}
