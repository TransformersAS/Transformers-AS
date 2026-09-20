package com.transformersas.marketplace.logistics.infrastructure.web.response;

/**
 * Resultado de una actualización recibida por webhook: APPLIED, RECORDED, OUT_OF_ORDER, DUPLICATE o IGNORED (tipo
 * desconocido). Todos son 200: el proveedor solo debe reintentar ante un error.
 */
public record WebhookResponse(String result) {
}
