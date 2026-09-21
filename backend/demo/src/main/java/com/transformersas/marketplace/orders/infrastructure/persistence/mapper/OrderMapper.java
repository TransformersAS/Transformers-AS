/** Conversión entre persistencia y modelos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.mapper;

import com.transformersas.marketplace.orders.domain.model.DeliverySnapshot;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderSummary;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderItemEntity;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }

    /**
     * Crea una entidad NUEVA (sin id). Solo sirve para insertar: para actualizar un pedido existente se usan los
     * UPDATE condicionados del repositorio; pasar este resultado a save() con un pedido ya guardado insertaría otra fila.
     */
    public static OrderEntity newEntity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.setAccountId(order.accountId());
        entity.setStatus(order.status());
        entity.setPaymentStatus(order.paymentStatus());
        entity.setTotal(order.total());
        entity.setStoreId(order.storeId());
        entity.setAddressId(order.addressId());
        entity.setShippingMethod(order.shippingMethod());
        DeliverySnapshot delivery = order.delivery();
        entity.setDeliveryRecipientName(delivery.recipientName());
        entity.setDeliveryStreet(delivery.street());
        entity.setDeliveryCity(delivery.city());
        entity.setDeliveryDepartment(delivery.department());
        entity.setDeliveryPostalCode(delivery.postalCode());
        entity.setDeliveryPhone(delivery.phone());
        entity.setTransactionId(order.transactionId());
        entity.setCreatedAt(order.createdAt());

        for (OrderItem item : order.items()) {
            OrderItemEntity itemEntity = new OrderItemEntity();
            itemEntity.setProductId(item.productId());
            itemEntity.setProductName(item.productName());
            itemEntity.setQuantity(item.quantity());
            itemEntity.setUnitPrice(item.unitPrice());
            itemEntity.setSubtotal(item.subtotal());
            entity.addItem(itemEntity);
        }
        return entity;
    }

    public static OrderSummary toSummary(OrderEntity entity) {
        return new OrderSummary(entity.getId(), entity.getStatus(), entity.getPaymentStatus(), entity.getTotal(),
                entity.getShippingMethod(), entity.getItems().size(), entity.getCreatedAt());
    }

    public static Order toDomain(OrderEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(item -> new OrderItem(item.getProductId(), item.getProductName(), item.getQuantity(),
                        item.getUnitPrice(), item.getSubtotal()))
                .toList();

        DeliverySnapshot delivery = new DeliverySnapshot(entity.getDeliveryRecipientName(),
                entity.getDeliveryStreet(), entity.getDeliveryCity(), entity.getDeliveryDepartment(),
                entity.getDeliveryPostalCode(), entity.getDeliveryPhone());

        return new Order(entity.getId(), entity.getAccountId(), entity.getStatus(), entity.getPaymentStatus(),
                entity.getTotal(), entity.getStoreId(), entity.getAddressId(), entity.getShippingMethod(),
                delivery, entity.getTransactionId(), entity.getCreatedAt(), items);
    }
}
