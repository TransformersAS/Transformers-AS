package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.dto.OrderActionCommand;
import com.transformersas.marketplace.orders.application.dto.OrderStatusResult;
import com.transformersas.marketplace.orders.application.dto.ReadyForDispatchResult;
import com.transformersas.marketplace.orders.application.dto.ShipmentOutcome;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * El vendedor confirma que terminó de preparar el pedido: IN_PREPARATION -> READY_FOR_DISPATCH (RF-120, RF-114,
 * RF-115, RF-121, A5).
 *
 * <p>Transacción 1: compare-and-set del estado + historial + auditoría + notificación al comprador, solo si no hay
 * novedades abiertas. Se confirma ANTES de hablar con logística. Después, FUERA de toda transacción, se solicita el
 * envío: si falla, el pedido sigue Listo para despacho y la respuesta informa el fallo (el vendedor reintenta con
 * POST shipment). Este caso de uso jamás mueve el pedido a Recogido: eso lo confirma logística (CU-24).
 */
@Service
public class MarkReadyForDispatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(MarkReadyForDispatchUseCase.class);

    private final OrderRepository orders;
    private final OrderIssueRepository issues;
    private final OrderChangeRecorder changes;
    private final ShipmentRequester shipmentRequester;
    private final TransactionTemplate transaction;

    public MarkReadyForDispatchUseCase(OrderRepository orders, OrderIssueRepository issues,
                                       OrderChangeRecorder changes, ShipmentRequester shipmentRequester,
                                       TransactionTemplate transaction) {
        this.orders = orders;
        this.issues = issues;
        this.changes = changes;
        this.shipmentRequester = shipmentRequester;
        this.transaction = transaction;
    }

    public ReadyForDispatchResult execute(OrderActionCommand command) {
        Order order = transaction.execute(status -> markReady(command));

        ShipmentOutcome shipment = shipmentRequester.request(order, command.actorId());
        log.info("Pedido listo para despacho orderId={} envío={}", order.id(), shipment.status());
        return new ReadyForDispatchResult(
                new OrderStatusResult(order.id(), OrderStatus.READY_FOR_DISPATCH, order.paymentStatus()), shipment);
    }

    private Order markReady(OrderActionCommand command) {
        Order order = orders.findByIdAndStoreId(command.orderId(), command.storeId())
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));

        order.status().requireTransitionTo(OrderStatus.READY_FOR_DISPATCH);

        List<OrderIssue> open = issues.findOpenByOrderId(order.id());
        if (!open.isEmpty()) {
            throw BusinessException.conflict("OPEN_ISSUES",
                    "El pedido tiene novedades abiertas: resuélvelas o cancela el pedido",
                    open.stream().map(issue -> Map.of("id", issue.id(), "type", issue.type().name(),
                            "description", issue.description())).toList());
        }

        if (!orders.transitionStatus(order.id(), OrderStatus.IN_PREPARATION, OrderStatus.READY_FOR_DISPATCH)) {
            throw BusinessException.conflict("ORDER_STATE_CONFLICT",
                    "El pedido cambió de estado mientras se procesaba la solicitud");
        }
        changes.recordTransition(order.id(), OrderStatus.IN_PREPARATION, OrderStatus.READY_FOR_DISPATCH,
                ActorType.SELLER, command.actorId(), "Preparación terminada");
        changes.notifyBuyer(order, "READY_FOR_DISPATCH", "Tu pedido está listo para despacho",
                "Tu pedido #" + order.id() + " está listo y será recogido por el servicio logístico.");
        return order;
    }
}
