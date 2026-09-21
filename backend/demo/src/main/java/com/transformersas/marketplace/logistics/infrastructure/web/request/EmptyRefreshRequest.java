package com.transformersas.marketplace.logistics.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

/**
 * Actualizar el seguimiento no lleva datos: el cuerpo es opcional y, si se envía, no admite propiedades. Así un
 * intento de fijar un estado a mano recibe 400 (A7/A8): los estados solo los informa el servicio logístico.
 */
public record EmptyRefreshRequest() implements StrictJsonRequest {
}
