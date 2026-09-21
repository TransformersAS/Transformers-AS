package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.domain.model.OrderIssue;

import java.time.LocalDateTime;

public record IssueResponse(
        Long id,
        Long orderId,
        String type,
        String description,
        String status,
        String reportedBy,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
    public static IssueResponse from(OrderIssue issue) {
        return new IssueResponse(issue.id(), issue.orderId(), issue.type().name(), issue.description(),
                issue.status().name(), issue.reportedByType().name(), issue.createdAt(), issue.resolvedAt());
    }
}
