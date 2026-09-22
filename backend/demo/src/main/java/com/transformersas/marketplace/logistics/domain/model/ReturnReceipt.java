package com.transformersas.marketplace.logistics.domain.model;

/** Referencia y guía de seguimiento que devuelve el servicio logístico al crear el retorno de una devolución. */
public record ReturnReceipt(String providerReturnId, String trackingCode) {
}
