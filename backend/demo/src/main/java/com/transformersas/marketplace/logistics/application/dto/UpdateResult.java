package com.transformersas.marketplace.logistics.application.dto;

/**
 * Resultado de procesar una actualización logística. DUPLICATE: el proveedor repitió una actualización ya procesada
 * (A5) y no tuvo ningún efecto. Los demás valores coinciden con TrackingOutcome.
 */
public enum UpdateResult { APPLIED, RECORDED, OUT_OF_ORDER, DUPLICATE }
