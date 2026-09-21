package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.inventory.application.usecase.GetStockLevelsUseCase;
import com.transformersas.marketplace.orders.application.dto.OrderActionCommand;
import com.transformersas.marketplace.orders.application.dto.OrderStatusResult;
import com.transformersas.marketplace.orders.domain.model.InventoryConsistency;
import com.transformersas.marketplace.orders.domain.model.IssueStatus;
import com.transformersas.marketplace.orders.domain.model.IssueType;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * El vendedor confirma que puede preparar un pedido: CONFIRMED -> IN_PREPARATION (RF-113, RF-123, A1, A3, A8).
 *
 * <p>Todo el cambio ocurre en UNA transacción: compare-and-set del estado, historial, auditoría y notificación;
 * cualquier fallo revierte todo y el pedido conserva su último estado válido. Si el inventario es inconsistente se
 * registra la novedad (esa transacción sí se confirma) y después se responde 409. La transición se decide con un
 * UPDATE condicionado por estado: dos peticiones simultáneas producen exactamente un éxito y un 409.
 */
@Service
public class StartPreparationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartPreparationUseCase.class);

    private final OrderRepository orders;
    private final OrderIssueRepository issues;
    private final GetStockLevelsUseCase stock;
    private final OrderChangeRecorder changes;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public StartPreparationUseCase(OrderRepository orders, OrderIssueRepository issues, GetStockLevelsUseCase stock,
                                   OrderChangeRecorder changes, AuditRecorder audit, TransactionTemplate transaction) {
        this.orders = orders;
        this.issues = issues;
        this.stock = stock;
        this.changes = changes;
        this.audit = audit;
        this.transaction = transaction;
    }

    public OrderStatusResult execute(OrderActionCommand command) {
        Attempt attempt = transaction.execute(status -> attempt(command));
        if (attempt.inconsistency() != null) {
            // La novedad ya está confirmada en BD; ahora se informa el conflicto (A3).
            throw BusinessException.conflict("INVENTORY_INCONSISTENT",
                    "El inventario del pedido es inconsistente; se registró una novedad que debe resolverse "
                            + "o cancelar el pedido",
                    Map.of("issueId", attempt.inconsistency().id()));
        }
        return attempt.result();
    }

    private Attempt attempt(OrderActionCommand command) {
        Order order = orders.findByIdAndStoreId(command.orderId(), command.storeId())
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));

        order.status().requireTransitionTo(OrderStatus.IN_PREPARATION);
        if (order.paymentStatus() != OrderPaymentStatus.APPROVED) {
            throw BusinessException.conflict("PAYMENT_NOT_APPROVED", "El pago del pedido no está aprobado");
        }

        String problem = inventoryProblem(order);
        if (problem != null) {
            var registration = issues.register(new OrderIssue(null, order.id(), IssueType.INVENTORY_INCONSISTENCY,
                    problem, IssueStatus.OPEN, ActorType.SYSTEM, null, LocalDateTime.now(), null, null));
            if (registration.created()) {
                audit.record(ActorType.SYSTEM, null, "ORDER_ISSUE_REGISTERED", "ORDER", order.id(),
                        AuditOutcome.SUCCESS, Map.of("issueId", registration.issue().id(),
                                "type", IssueType.INVENTORY_INCONSISTENCY.name()));
            }
            log.warn("Inventario inconsistente al preparar orderId={} issueId={}", order.id(), registration.issue().id());
            return new Attempt(null, registration.issue());
        }

        // Compare-and-set: solo gana quien encuentra el pedido todavía en CONFIRMED.
        if (!orders.transitionStatus(order.id(), OrderStatus.CONFIRMED, OrderStatus.IN_PREPARATION)) {
            throw BusinessException.conflict("ORDER_STATE_CONFLICT",
                    "El pedido cambió de estado mientras se procesaba la solicitud");
        }
        changes.recordTransition(order.id(), OrderStatus.CONFIRMED, OrderStatus.IN_PREPARATION, ActorType.SELLER,
                command.actorId(), "Preparación iniciada");
        changes.notifyBuyer(order, "IN_PREPARATION", "Tu pedido está en preparación",
                "El vendedor comenzó a preparar tu pedido #" + order.id() + ".");
        log.info("Pedido en preparación orderId={} storeId={}", order.id(), order.storeId());
        return new Attempt(new OrderStatusResult(order.id(), OrderStatus.IN_PREPARATION, order.paymentStatus()), null);
    }

    /** Devuelve la descripción del problema de inventario, o null si todo es consistente. */
    private String inventoryProblem(Order order) {
        Map<Long, Integer> levels = stock.execute(order.items().stream().map(OrderItem::productId).distinct().toList());
        List<String> problems = order.items().stream()
                .filter(item -> !InventoryConsistency.isConsistent(levels.get(item.productId())))
                .map(item -> "«" + item.productName() + "»" + (levels.containsKey(item.productId())
                        ? " con existencias inválidas" : " ya no existe en el catálogo"))
                .toList();
        return problems.isEmpty() ? null
                : "Inventario inconsistente: " + String.join("; ", problems);
    }

    private record Attempt(OrderStatusResult result, OrderIssue inconsistency) {
    }
}
