package com.transformersas.marketplace.notifications.domain.model;

/** Estado del aviso al servicio externo. No afecta la disponibilidad de la notificación interna. */
public enum ExternalStatus { PENDING, SENT, FAILED }
