package com.transformersas.marketplace.logistics.application.dto;

import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.shared.audit.ActorType;

/** Solicitud de creación de envío con el actor que la origina (para la auditoría). */
public record CreateShipmentCommand(ShipmentRequest request, ActorType actorType, Long actorId) {
}
