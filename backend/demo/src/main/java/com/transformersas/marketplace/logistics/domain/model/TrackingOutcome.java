package com.transformersas.marketplace.logistics.domain.model;

/**
 * Efecto de una actualización. APPLIED movió el estado; RECORDED se guardó como información sin mover el estado;
 * OUT_OF_ORDER retrocedería o contradiría el estado vigente (A6) y se conserva solo para trazabilidad.
 */
public enum TrackingOutcome { APPLIED, RECORDED, OUT_OF_ORDER }
