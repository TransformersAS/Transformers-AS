package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.inventory.application.usecase.RestoreStockUseCase;
import com.transformersas.marketplace.logistics.application.usecase.GetShipmentUseCase;
import com.transformersas.marketplace.orders.application.dto.CancelOrderCommand;
import com.transformersas.marketplace.orders.application.dto.CancelOrderResult;
import com.transformersas.marketplace.orders.application.dto.CancelOrderResult.RefundOutcome;
import com.transformersas.marketplace.orders.application.dto.OrderStatusResult;
import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderCancellation;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderCancellationRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cancelación completa de un pedido antes del despacho (D10, RF-122, RF-123, A2, RNF-015, RNF-043). Genérico: lo usa
 * CU-23 (vendedor) y lo usará CU-11 (comprador).
 *
 * <p>Transacción 1 (todo o nada): compare-and-set del estado a CANCELLED, registro de la cancelación, reposición
 * atómica del stock, solicitud de reembolso PENDING con payment_status = REFUND_PENDING, historial, auditoría y
 * notificaciones al comprador y a la tienda. Solo desde CONFIRMED o IN_PREPARATION y sin envío: nunca hay despacho
 * parcial. Después del commit y FUERA de transacción se procesa el reembolso: sea cual sea su resultado (éxito,
 * pendiente o fallo), el pedido sigue CANCELLED y no se revierte nada.
 */
@Service
public class CancelOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderUseCase.class);

    private final OrderRepository orders;
    private final OrderCancellationRepository cancellations;
    private final GetShipmentUseCase shipments;
    private final RestoreStockUseCase restoreStock;
    private final RequestRefundUseCase refunds;
    private final OrderChangeRecorder changes;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public CancelOrderUseCase(OrderRepository orders, OrderCancellationRepository cancellations,
                              GetShipmentUseCase shipments, RestoreStockUseCase restoreStock,
                              RequestRefundUseCase refunds, OrderChangeRecorder changes, AuditRecorder audit,
                              TransactionTemplate transaction) {
        this.orders = orders;
        this.cancellations = cancellations;
        this.shipments = shipments;
        this.restoreStock = restoreStock;
        this.refunds = refunds;
        this.changes = changes;
        this.audit = audit;
        this.transaction = transaction;
    }

    public CancelOrderResult execute(CancelOrderCommand command) {
        Cancelled cancelled = transaction.execute(status -> cancel(command));

        RefundOutcome refund = settleRefund(cancelled);
        OrderPaymentStatus paymentStatus = switch (refund.status()) {
            case NOT_APPLICABLE -> cancelled.order().paymentStatus();
            case COMPLETED -> OrderPaymentStatus.REFUNDED;
            case PENDING, FAILED -> OrderPaymentStatus.REFUND_PENDING;
        };
        return new CancelOrderResult(new OrderStatusResult(cancelled.order().id(), OrderStatus.CANCELLED, paymentStatus),
                refund);
    }

    private Cancelled cancel(CancelOrderCommand command) {
        Order order = load(command);
        order.status().requireTransitionTo(OrderStatus.CANCELLED); // solo CONFIRMED o IN_PREPARATION
        if (shipments.execute(order.id()).isPresent()) {
            throw BusinessException.conflict("ORDER_HAS_SHIPMENT",
                    "El pedido ya tiene un envío creado y no puede cancelarse");
        }

        OrderStatus from = order.status();
        if (!orders.transitionStatus(order.id(), from, OrderStatus.CANCELLED)) {
            throw BusinessException.conflict("ORDER_STATE_CONFLICT",
                    "El pedido cambió de estado mientras se procesaba la solicitud");
        }

        ActorType actorType = command.initiator() == CancellationInitiator.SELLER ? ActorType.SELLER : ActorType.BUYER;
        cancellations.insert(new OrderCancellation(null, order.id(), command.initiator(), command.reason(),
                command.details(), command.actorId(), CorrelationContext.current(), LocalDateTime.now()));

        // Reposición atómica del stock. Un producto que ya no existe se omite: no debe impedir la cancelación.
        Map<Long, Integer> restored = new LinkedHashMap<>();
        List<Long> skipped = new ArrayList<>();
        for (OrderItem item : order.items()) {
            if (restoreStock.execute(item.productId(), item.quantity())) {
                restored.merge(item.productId(), item.quantity(), Integer::sum);
            } else {
                skipped.add(item.productId());
            }
        }

        boolean refundRequired = order.paymentStatus() == OrderPaymentStatus.APPROVED;
        RefundCommand refundCommand = refundCommand(order, actorType, command.actorId());
        if (refundRequired) {
            refunds.registerPending(refundCommand);
            if (!orders.transitionPaymentStatus(order.id(), OrderPaymentStatus.APPROVED, OrderPaymentStatus.REFUND_PENDING)) {
                throw BusinessException.conflict("ORDER_STATE_CONFLICT",
                        "El pago del pedido cambió mientras se procesaba la solicitud");
            }
        }

        changes.recordTransition(order.id(), from, OrderStatus.CANCELLED, actorType, command.actorId(),
                "Cancelado: " + command.reason());
        audit.record(actorType, command.actorId(), "ORDER_CANCELLED", "ORDER", order.id(), AuditOutcome.SUCCESS,
                Map.of("initiator", command.initiator().name(), "reasonCode", command.reason().name(),
                        "fromStatus", from.name(), "restoredUnits", restored, "skippedProducts", skipped,
                        "refundRequired", refundRequired));
        String cause = command.initiator() == CancellationInitiator.SELLER
                ? "El vendedor no pudo cumplir tu pedido #" + order.id() + " y fue cancelado."
                : "Tu pedido #" + order.id() + " fue cancelado.";
        changes.notifyBuyer(order, "CANCELLED", "Tu pedido fue cancelado",
                cause + (refundRequired ? " Estamos gestionando tu reembolso." : ""));
        changes.notifyStore(order, "CANCELLED", "Pedido cancelado",
                "El pedido #" + order.id() + " fue cancelado y sus unidades volvieron al inventario.");
        log.info("Pedido cancelado orderId={} initiator={} reason={}", order.id(), command.initiator(), command.reason());
        return new Cancelled(order, refundRequired ? refundCommand : null);
    }

    private Order load(CancelOrderCommand command) {
        var order = command.storeId() != null
                ? orders.findByIdAndStoreId(command.orderId(), command.storeId())
                : orders.findById(command.orderId());
        return order.orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));
    }

    private static RefundCommand refundCommand(Order order, ActorType actorType, Long actorId) {
        return new RefundCommand(order.id(), order.total(), "order-cancel-" + order.id(), actorType, actorId);
    }

    /** Procesa el reembolso tras el commit. Nunca lanza ni revierte la cancelación. */
    private RefundOutcome settleRefund(Cancelled cancelled) {
        if (cancelled.refundCommand() == null) {
            return new RefundOutcome(RefundOutcome.Status.NOT_APPLICABLE, "El pedido no tenía un pago aprobado");
        }
        try {
            Refund refund = refunds.execute(cancelled.refundCommand());
            if (refund.status() == RefundStatus.COMPLETED) {
                orders.transitionPaymentStatus(cancelled.order().id(), OrderPaymentStatus.REFUND_PENDING,
                        OrderPaymentStatus.REFUNDED);
                return new RefundOutcome(RefundOutcome.Status.COMPLETED, "Reembolso completado");
            }
            if (refund.status() == RefundStatus.FAILED) {
                return new RefundOutcome(RefundOutcome.Status.FAILED,
                        "El reembolso no pudo procesarse ahora; quedó registrado para reintentarse");
            }
            return new RefundOutcome(RefundOutcome.Status.PENDING, "Reembolso en proceso");
        } catch (RuntimeException unexpected) {
            // La cancelación ya está confirmada: el reembolso queda PENDING registrado y se reintenta después.
            log.error("Error procesando el reembolso del pedido cancelado orderId={}", cancelled.order().id(), unexpected);
            return new RefundOutcome(RefundOutcome.Status.FAILED,
                    "El reembolso no pudo procesarse ahora; quedó registrado para reintentarse");
        }
    }

    private record Cancelled(Order order, RefundCommand refundCommand) {
    }
}
