package com.transformersas.marketplace.logistics.domain.model;

/** Por dónde llegó la actualización: webhook del proveedor, consulta del marketplace o registro inicial propio. */
public enum TrackingSource { WEBHOOK, POLLING, SYSTEM }
