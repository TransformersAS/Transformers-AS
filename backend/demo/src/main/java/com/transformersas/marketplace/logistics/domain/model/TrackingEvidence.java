package com.transformersas.marketplace.logistics.domain.model;

/** Evidencia o confirmación que aporta logística (firma, foto, código); reference nunca es un dato personal. */
public record TrackingEvidence(String type, String reference) {
}
