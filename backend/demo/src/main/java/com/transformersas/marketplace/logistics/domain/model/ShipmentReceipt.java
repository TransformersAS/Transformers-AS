package com.transformersas.marketplace.logistics.domain.model;

/** Identificación y guía de seguimiento devueltas por el servicio logístico al crear el envío (RF-115). */
public record ShipmentReceipt(String shipmentId, String trackingCode) {
}
