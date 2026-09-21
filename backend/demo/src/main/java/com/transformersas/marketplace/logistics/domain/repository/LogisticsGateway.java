package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.ReturnMethod;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;

import java.util.List;

/**
 * Puerto hacia el servicio logístico externo: crea envíos (CU-23) y consulta el seguimiento de envíos (CU-24) y de
 * retornos (CU-25). Las actualizaciones que el proveedor empuja por webhook no pasan por aquí: entran por el
 * controlador y comparten los mismos casos de uso que la consulta.
 */
public interface LogisticsGateway {

    /**
     * Crea el envío. Idempotente: la misma idempotencyKey devuelve el mismo envío.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    ShipmentReceipt createShipment(ShipmentRequest request, String idempotencyKey);

    /**
     * Consulta las actualizaciones conocidas de un envío (lectura idempotente, sin efectos en el proveedor).
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    List<TrackingUpdate> fetchShipmentUpdates(String providerShipmentId);

    /**
     * Consulta las actualizaciones conocidas del retorno de una devolución.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    List<ReturnTrackingUpdate> fetchReturnUpdates(String providerReturnId);

    /**
     * Métodos de retorno disponibles para la devolución de un pedido a una tienda (RF-109). Lectura sin efectos en el
     * proveedor. La lista puede cambiar de una consulta a otra: un método ofrecido antes puede dejar de estarlo.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    List<ReturnMethod> fetchReturnMethods(Long orderId, Long storeId);

    /**
     * Crea el retorno de una devolución. Idempotente: la misma idempotencyKey ("return-{id}") devuelve el mismo retorno.
     * Un 4xx, entre ellos un método que ya no está disponible, es un rechazo definitivo: hay que elegir otro método.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    ReturnReceipt createReturn(ReturnRequestData request, String idempotencyKey);
}
