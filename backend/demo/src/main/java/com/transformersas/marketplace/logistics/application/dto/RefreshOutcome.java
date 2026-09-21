package com.transformersas.marketplace.logistics.application.dto;

/**
 * Resultado de consultar al servicio logístico. UNAVAILABLE conserva el último seguimiento conocido (A7);
 * THROTTLED evita consultas seguidas del mismo envío; NOT_TRACKED significa que no hay envío o que ya terminó.
 */
public enum RefreshOutcome { UPDATED, NO_CHANGES, UNAVAILABLE, THROTTLED, NOT_TRACKED }
