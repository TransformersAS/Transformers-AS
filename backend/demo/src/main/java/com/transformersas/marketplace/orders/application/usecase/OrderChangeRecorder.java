package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Efectos que acompañan a todo cambio de estado, escritos en la MISMA transacción que el cambio (RNF-015):
 * historial (D6), auditoría (D7) y notificaciones (D9). Se invoca solo después de ganar el compare-and-set.
 */
@Component
class OrderChangeRecorder {

    private final OrderStatusHistoryRepository history;
    private final AuditRecorder audit;
    private final PublishNotificationUseCase notifications;

    OrderChangeRecorder(OrderStatusHistoryRepository history, AuditRecorder audit,
                        PublishNotificationUseCase notifications) {
        this.history = history;
        this.audit = audit;
        this.notifications = notifications;
    }

    void recordTransition(Long orderId, OrderStatus from, OrderStatus to, ActorType actorType, Long actorId,
                          String reason) {
        history.append(new OrderStatusHistoryEntry(null, orderId, from, to, actorType, actorId, reason,
                CorrelationContext.current(), LocalDateTime.now()));
        audit.record(actorType, actorId, "ORDER_STATUS_CHANGED", "ORDER", orderId, AuditOutcome.SUCCESS,
                Map.of("from", from.name(), "to", to.name()));
    }

    /** eventKey = order-{id}-{event}: un mismo cambio de estado nunca genera dos notificaciones. */
    void notifyBuyer(Order order, String event, String title, String message) {
        notifications.execute(new PublishNotificationCommand(RecipientType.BUYER, order.accountId(),
                "ORDER_" + event, title, message, "ORDER", String.valueOf(order.id()),
                "order-" + order.id() + "-" + event));
    }

    void notifyStore(Order order, String event, String title, String message) {
        notifications.execute(new PublishNotificationCommand(RecipientType.STORE, order.storeId(),
                "ORDER_" + event, title, message, "ORDER", String.valueOf(order.id()),
                "order-" + order.id() + "-" + event + "-STORE"));
    }
}
