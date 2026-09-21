package com.transformersas.marketplace.logistics.application.dto;

import com.transformersas.marketplace.logistics.domain.model.Shipment;

/** created es false cuando el envío ya existía y se reutilizó su referencia (A6). */
public record ShipmentResult(Shipment shipment, boolean created) {
}
