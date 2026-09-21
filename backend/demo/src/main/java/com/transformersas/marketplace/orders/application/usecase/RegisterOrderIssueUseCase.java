package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.dto.IssueCommands;
import com.transformersas.marketplace.orders.domain.model.IssueStatus;
import com.transformersas.marketplace.orders.domain.model.IssueType;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * El vendedor registra una novedad de preparación (RF-121). Solo mientras el pedido está en CONFIRMED o
 * IN_PREPARATION. La novedad y su auditoría se confirman juntas. Una inconsistencia de inventario ya abierta no se
 * duplica: se responde 409 con la novedad existente.
 */
@Service
public class RegisterOrderIssueUseCase {

    private final OrderRepository orders;
    private final OrderIssueRepository issues;
    private final AuditRecorder audit;

    public RegisterOrderIssueUseCase(OrderRepository orders, OrderIssueRepository issues, AuditRecorder audit) {
        this.orders = orders;
        this.issues = issues;
        this.audit = audit;
    }

    @Transactional
    public OrderIssue execute(IssueCommands.Register command) {
        Order order = orders.findByIdAndStoreId(command.orderId(), command.storeId())
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));
        if (!order.status().allowsPreparationIssues()) {
            throw BusinessException.conflict("ISSUE_NOT_ALLOWED_IN_STATUS",
                    "Solo se registran novedades en pedidos Confirmados o En preparación");
        }

        var registration = issues.register(new OrderIssue(null, order.id(), command.type(), command.description().strip(),
                IssueStatus.OPEN, ActorType.SELLER, command.actorId(), LocalDateTime.now(), null, null));
        if (!registration.created()) {
            throw BusinessException.conflict("ISSUE_ALREADY_OPEN",
                    "Ya existe una novedad abierta de inconsistencia de inventario para este pedido",
                    Map.of("issueId", registration.issue().id()));
        }

        audit.record(ActorType.SELLER, command.actorId(), "ORDER_ISSUE_REGISTERED", "ORDER", order.id(),
                AuditOutcome.SUCCESS, Map.of("issueId", registration.issue().id(), "type", command.type().name()));
        return registration.issue();
    }
}
