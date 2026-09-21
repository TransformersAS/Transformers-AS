package com.transformersas.marketplace.orders.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

/**
 * Las acciones de estado (iniciar preparación, listo para despacho, resolver novedad, solicitar envío) no llevan
 * datos: el cuerpo es opcional y, si se envía, no admite propiedades. Así un intento de cambiar dirección o método
 * de envío recibe 400 (A4).
 */
public record EmptyActionRequest() implements StrictJsonRequest {
}
