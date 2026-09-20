package com.transformersas.marketplace.logistics.application.dto;

import java.time.LocalDateTime;

/**
 * Evento de aplicación: logística confirmó la entrega de la devolución al vendedor y CU-25 terminó (paso 14). Se
 * publica dentro de la transacción del cambio; CU-19 lo escucha para continuar con En inspección y las 24 horas.
 */
public record ReturnDeliveredToSeller(Long returnId, Long buyerAccountId, Long storeId, LocalDateTime deliveredAt) {
}
