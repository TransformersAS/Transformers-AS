package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.dto.IssueCommands;
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
 * El vendedor marca una novedad como resuelta (RF-121). Compare-and-set OPEN -> RESOLVED: resolver dos veces
 * la misma novedad produce un éxito y un 409.
 */
@Service
public class ResolveOrderIssueUseCase {

    private final OrderRepository orders;
    private final OrderIssueRepository issues;
    private final AuditRecorder audit;

    public ResolveOrderIssueUseCase(OrderRepository orders, OrderIssueRepository issues, AuditRecorder audit) {
        this.orders = orders;
        this.issues = issues;
        this.audit = audit;
    }

    @Transactional
    public OrderIssue execute(IssueCommands.Resolve command) {
        Order order = orders.findByIdAndStoreId(command.orderId(), command.storeId())
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));
        if (issues.findByIdAndOrderId(command.issueId(), order.id()).isEmpty()) {
            throw BusinessException.notFound("ISSUE_NOT_FOUND", "La novedad no existe");
        }
        if (!order.status().allowsPreparationIssues()) {
            throw BusinessException.conflict("ISSUE_NOT_ALLOWED_IN_STATUS",
                    "Solo se gestionan novedades en pedidos Confirmados o En preparación");
        }
        if (!issues.resolve(command.issueId(), command.actorId(), LocalDateTime.now())) {
            throw BusinessException.conflict("ISSUE_ALREADY_RESOLVED", "La novedad ya estaba resuelta");
        }

        audit.record(ActorType.SELLER, command.actorId(), "ORDER_ISSUE_RESOLVED", "ORDER", order.id(),
                AuditOutcome.SUCCESS, Map.of("issueId", command.issueId()));
        return issues.findByIdAndOrderId(command.issueId(), order.id()).orElseThrow();
    }
}
