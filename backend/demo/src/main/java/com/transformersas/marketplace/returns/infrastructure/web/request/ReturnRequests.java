package com.transformersas.marketplace.returns.infrastructure.web.request;

/** Cuerpos JSON de los endpoints de devoluciones. Se validan campo por campo en el dominio, no con anotaciones. */
public final class ReturnRequests {
    private ReturnRequests() {
    }

    /** Solicitud de devolución sin imágenes (con imágenes va como formulario multipart). */
    public record Request(Long orderId, Long orderItemId, String reason, String description) {
    }

    /** Respuesta del comprador a una solicitud de información. */
    public record Answer(String text) {
    }

    /** Lo que el vendedor le pide al comprador. */
    public record InformationRequest(String message) {
    }

    /** Problema que la tienda encontró al inspeccionar lo devuelto. */
    public record Problem(String description) {
    }

    /** Justificación de una decisión del vendedor (obligatoria al rechazar, opcional al aprobar). */
    public record Decision(String note) {
    }
}
