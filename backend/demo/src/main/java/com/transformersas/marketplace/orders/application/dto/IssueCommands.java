package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.orders.domain.model.IssueType;

/** Comandos de novedades de preparación. La tienda y el actor salen de la identidad del vendedor. */
public final class IssueCommands {

    private IssueCommands() {
    }

    public record Register(Long orderId, Long storeId, Long actorId, IssueType type, String description) {
    }

    public record Resolve(Long orderId, Long storeId, Long actorId, Long issueId) {
    }
}
