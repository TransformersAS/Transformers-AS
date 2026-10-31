package com.transformersas.marketplace.orders.application.port;

import com.transformersas.marketplace.orders.application.dto.BuyerRefund;
import java.util.List;

public interface BuyerRefundQuery {
    /** El llamador debe autorizar primero el pedido. Solo lectura de registros existentes. */
    List<BuyerRefund> findByOrderId(Long orderId);
}
