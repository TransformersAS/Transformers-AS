package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.application.usecase.FindOwnOrders;
import com.transformersas.marketplace.orders.application.usecase.RequestOrderCancellation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final FindOwnOrders orders;

    private final RequestOrderCancellation cancellation;

    public OrderController(FindOwnOrders orders, RequestOrderCancellation cancellation) {
        this.orders = orders;
        this.cancellation = cancellation;
    }

    @PostMapping("/{id}/cancellation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestCancellation(@PathVariable Long id, @AuthenticationPrincipal AccountPrincipal principal) {
        cancellation.execute(id, principal);
    }

    public record OrderSummary(Long id, OrderStatus status, BigDecimal total,
                               String shippingMethod, LocalDateTime createdAt) {
        static OrderSummary from(Order order) {
            return new OrderSummary(order.id(), order.status(), order.total(), order.shippingMethod(), order.createdAt());
        }
    }

    public record OrderDetail(Long id, OrderStatus status, BigDecimal total, Long addressId,
                              String shippingMethod, String transactionId, LocalDateTime createdAt, List<OrderItem> items) {
        static OrderDetail from(Order order) {
            return new OrderDetail(order.id(), order.status(), order.total(), order.addressId(), order.shippingMethod(),
                    order.transactionId(), order.createdAt(), order.items());
        }
    }

    @GetMapping
    public List<OrderSummary> list(@AuthenticationPrincipal AccountPrincipal principal) {
        return orders.list(principal).stream().map(OrderSummary::from).toList();
    }

    @GetMapping("/{id}")
    public OrderDetail detail(@PathVariable Long id, @AuthenticationPrincipal AccountPrincipal principal) {
        return OrderDetail.from(orders.detail(id, principal));
    }
}
