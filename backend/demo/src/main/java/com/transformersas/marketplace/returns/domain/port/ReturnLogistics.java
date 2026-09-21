package com.transformersas.marketplace.returns.domain.port;

import java.util.List;

/**
 * El retorno físico de una devolución aprobada, en el servicio logístico (RF-109). Devoluciones no conoce el gateway de
 * logística: solo este puerto, que implementa un adaptador. Las llamadas son externas y lentas: quien las use NO debe
 * tener una transacción de base de datos abierta.
 */
public interface ReturnLogistics {

    /** Un método de retorno ofrecido por logística. */
    record Method(String code, String label) {
    }

    /**
     * Lo que logística necesita para crear el retorno. El destino es la tienda (solo su id): el servicio logístico resuelve
     * el lugar de entrega. El origen es la dirección de recogida del comprador.
     */
    record Shipment(Long returnId, Long orderId, Long storeId, String methodCode, OrderForReturn.Pickup pickup,
                    String itemName, int quantity) {

        /** Clave de idempotencia estable por devolución: repetir la llamada nunca crea otro retorno. */
        public String idempotencyKey() {
            return "return-" + returnId;
        }
    }

    /** Referencia y guía del retorno creado en logística. */
    record Receipt(String providerReturnId, String trackingCode) {
    }

    /** Logística no respondió o falló de forma temporal: se puede reintentar. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Logística rechazó la solicitud de forma definitiva (por ejemplo, un método que ya no está disponible). */
    class RejectedException extends RuntimeException {
        public RejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Métodos disponibles ahora para devolver ese pedido a esa tienda. */
    List<Method> methods(Long orderId, Long storeId);

    /** Crea el retorno; idempotente por {@link Shipment#idempotencyKey()}. */
    Receipt createReturn(Shipment shipment);
}
