package com.transformersas.marketplace.logistics.application.dto;

import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;

/** Quién consulta el seguimiento de una devolución: su comprador o la tienda dueña. Nadie más lo ve (RNF-010). */
public record ReturnViewer(Long buyerAccountId, Long storeId) {

    public static ReturnViewer buyer(Long accountId) {
        return new ReturnViewer(accountId, null);
    }

    public static ReturnViewer seller(Long storeId) {
        return new ReturnViewer(null, storeId);
    }

    public boolean canView(ReturnShipment shipment) {
        return buyerAccountId != null ? buyerAccountId.equals(shipment.buyerAccountId())
                : storeId != null && storeId.equals(shipment.storeId());
    }
}
