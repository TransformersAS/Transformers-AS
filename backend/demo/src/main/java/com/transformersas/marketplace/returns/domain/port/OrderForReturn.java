package com.transformersas.marketplace.returns.domain.port;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lo que devoluciones necesita saber de un pedido. {@code delivered} dice si está ENTREGADO y {@code deliveredAt} es la
 * fecha real de entrega, o nula si el pedido consta como entregado pero no se pudo determinar cuándo. {@code pickup} es la
 * dirección de entrega del pedido (el snapshot de orders.delivery_*), que es de donde se recoge la devolución; nula si el
 * pedido no la conserva.
 */
public record OrderForReturn(Long orderId, Long buyerAccountId, Long storeId, boolean delivered,
                             LocalDateTime deliveredAt, List<ReturnLine> lines, Pickup pickup) {

    /** Dirección de recogida: dato personal, por eso su toString no lo expone. */
    public record Pickup(String name, String street, String city, String department, String postalCode,
                         String phone) {
        @Override
        public String toString() {
            return "Pickup[redacted]";
        }
    }

    public OrderForReturn {
        lines = List.copyOf(lines);
    }

    /** Un pedido sin dirección de recogida, para lo que no la necesita (elegibilidad). */
    public OrderForReturn(Long orderId, Long buyerAccountId, Long storeId, boolean delivered,
                          LocalDateTime deliveredAt, List<ReturnLine> lines) {
        this(orderId, buyerAccountId, storeId, delivered, deliveredAt, lines, null);
    }

    public Optional<ReturnLine> line(Long orderItemId) {
        return lines.stream().filter(line -> line.orderItemId().equals(orderItemId)).findFirst();
    }
}
