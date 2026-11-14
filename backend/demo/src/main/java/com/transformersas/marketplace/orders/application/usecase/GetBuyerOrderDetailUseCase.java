package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.port.BuyerRefundQuery;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.dto.BuyerOrderDetail;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetBuyerOrderDetailUseCase {
    private final FindOwnOrders orders;
    private final BuyerRefundQuery refunds;
    private final OrderStatusHistoryRepository history;

    public GetBuyerOrderDetailUseCase(FindOwnOrders orders, OrderStatusHistoryRepository history, BuyerRefundQuery refunds) {
        this.orders = orders;
        this.refunds = refunds;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public BuyerOrderDetail execute(Long id, AccountPrincipal principal) {
        // Autorizar antes de consultar cualquier registro del historial.
        Order order = orders.detail(id, principal);
        return new BuyerOrderDetail(order, history.findByOrderId(order.id()).stream()
                .map(BuyerOrderDetail.History::from).toList(), refunds.findByOrderId(order.id()));
    }
}
