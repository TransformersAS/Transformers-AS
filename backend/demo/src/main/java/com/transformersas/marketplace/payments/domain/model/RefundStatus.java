package com.transformersas.marketplace.payments.domain.model;

/** PENDING: registrado o aceptado por el proveedor sin confirmar. FAILED: el último intento falló y es reintentable. */
public enum RefundStatus { PENDING, COMPLETED, FAILED }
