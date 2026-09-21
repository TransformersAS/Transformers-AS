package com.transformersas.marketplace.returns.domain.port;

/**
 * Deja registrado el seguimiento logístico de una devolución cuyo retorno ya existe en logística (CU-25, pasos 1 y 2):
 * queda en Recogida pendiente. Idempotente por devolución. Participa de la transacción del llamador, así que se confirma o
 * se revierte junto con la elección del método.
 */
public interface ReturnShipmentRegistrar {

    void register(Long returnId, Long buyerAccountId, Long storeId, String providerReturnId, String trackingCode);
}
