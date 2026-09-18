/** Conversión entre persistencia y modelos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.mapper;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderItemEntity;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }


    public static OrderEntity toEntity(
            Order order
    ) {

        OrderEntity entity =
                new OrderEntity();


        entity.setStatus(
                order.status()
        );

        entity.setTotal(
                order.total()
        );

        entity.setAddressId(
                order.addressId()
        );

        entity.setShippingMethod(
                order.shippingMethod()
        );

        entity.setTransactionId(
                order.transactionId()
        );

        entity.setCreatedAt(
                order.createdAt()
        );


        for (OrderItem item : order.items()) {

            OrderItemEntity itemEntity =
                    new OrderItemEntity();


            itemEntity.setProductId(
                    item.productId()
            );

            itemEntity.setProductName(
                    item.productName()
            );

            itemEntity.setQuantity(
                    item.quantity()
            );

            itemEntity.setUnitPrice(
                    item.unitPrice()
            );

            itemEntity.setSubtotal(
                    item.subtotal()
            );


            entity.addItem(
                    itemEntity
            );
        }


        return entity;
    }


    public static Order toDomain(
            OrderEntity entity
    ) {

        List<OrderItem> items =
                entity.getItems()
                        .stream()
                        .map(
                                item ->
                                        new OrderItem(
                                                item.getProductId(),
                                                item.getProductName(),
                                                item.getQuantity(),
                                                item.getUnitPrice(),
                                                item.getSubtotal()
                                        )
                        )
                        .toList();


        return new Order(
                entity.getId(),
                entity.getStatus(),
                entity.getTotal(),
                entity.getAddressId(),
                entity.getShippingMethod(),
                entity.getTransactionId(),
                entity.getCreatedAt(),
                items
        );
    }
}