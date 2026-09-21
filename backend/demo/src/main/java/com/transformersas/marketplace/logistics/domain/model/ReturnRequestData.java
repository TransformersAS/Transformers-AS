package com.transformersas.marketplace.logistics.domain.model;

import java.util.List;

/**
 * Datos necesarios para que el servicio logístico cree el retorno de una devolución aprobada (RF-109). Lo construye el módulo
 * de devoluciones; logística nunca lee sus tablas. El destino es la tienda: solo se envía su identificador y el servicio
 * logístico resuelve el lugar de entrega. El origen es la dirección de recogida del comprador, que sale del snapshot de
 * entrega del pedido (orders.delivery_*), el mismo que se usó al enviárselo.
 *
 * @param returnId   id de la devolución; con él se forma la clave de idempotencia
 * @param orderId    pedido de la línea devuelta, como referencia
 * @param storeId    tienda a la que vuelve el producto
 * @param methodCode código de uno de los métodos que devolvió {@code fetchReturnMethods}
 * @param pickup     dónde recoger (dato personal, por eso su {@code toString} no lo expone)
 * @param items      lo que se devuelve: nombre y cantidad
 */
public record ReturnRequestData(Long returnId, Long orderId, Long storeId, String methodCode,
                                ShipmentRequest.Recipient pickup, List<ShipmentRequest.Item> items) {

    public ReturnRequestData {
        if (returnId == null || returnId <= 0 || orderId == null || orderId <= 0 || storeId == null || storeId <= 0
                || methodCode == null || methodCode.isBlank() || pickup == null || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Solicitud de retorno inválida");
        }
        items = List.copyOf(items);
    }

    /** Clave de idempotencia estable por devolución: repetir la solicitud nunca crea otro retorno. */
    public String idempotencyKey() {
        return "return-" + returnId;
    }
}
