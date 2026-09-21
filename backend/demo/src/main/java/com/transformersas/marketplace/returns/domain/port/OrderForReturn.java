package com.transformersas.marketplace.returns.domain.port;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lo que devoluciones necesita saber de un pedido. {@code delivered} dice si está ENTREGADO y {@code deliveredAt} es la
 * fecha real de entrega, o nula si el pedido consta como entregado pero no se pudo determinar cuándo.
 */
public record OrderForReturn(Long orderId, Long buyerAccountId, Long storeId, boolean delivered,
                             LocalDateTime deliveredAt, List<ReturnLine> lines) {

    public OrderForReturn {
        lines = List.copyOf(lines);
    }

    public Optional<ReturnLine> line(Long orderItemId) {
        return lines.stream().filter(line -> line.orderItemId().equals(orderItemId)).findFirst();
    }
}
