package com.transformersas.marketplace.orders.infrastructure.web.request;

import com.transformersas.marketplace.orders.domain.model.CancellationReason;
import com.transformersas.marketplace.shared.web.StrictJsonRequest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Imposibilidad de cumplir el pedido (RF-122). details es obligatorio con el motivo OTHER (lo valida el caso de uso).
 * No admite propiedades desconocidas.
 */
public record CancelOrderRequest(
        @NotNull CancellationReason reasonCode,
        @Size(max = 1000) String details
) implements StrictJsonRequest {
}
