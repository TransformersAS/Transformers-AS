package com.transformersas.marketplace.orders.infrastructure.web.request;

import com.transformersas.marketplace.orders.domain.model.IssueType;
import com.transformersas.marketplace.shared.web.StrictJsonRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Novedad de preparación (RF-121). */
public record RegisterIssueRequest(
        @NotNull IssueType type,
        @NotBlank @Size(max = 1000) String description
) implements StrictJsonRequest {
}
