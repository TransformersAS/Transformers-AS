/** Representaciones JPA privadas de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.entity;

import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Los cambios de status y payment_status se hacen SOLO con los UPDATE condicionados del repositorio Spring Data
 * (compare-and-set). Tienda, comprador y snapshot de entrega no son actualizables (updatable = false).
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Comprador del pedido. Nulo solo en pedidos históricos cuyo dueño se desconoce.
    @Column(name = "account_id", updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private OrderPaymentStatus paymentStatus;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "address_id", nullable = false)
    private Long addressId;

    @Column(name = "shipping_method", nullable = false, length = 30)
    private String shippingMethod;

    @Column(name = "delivery_recipient_name", nullable = false, updatable = false)
    private String deliveryRecipientName;

    @Column(name = "delivery_street", nullable = false, updatable = false)
    private String deliveryStreet;

    @Column(name = "delivery_city", nullable = false, updatable = false)
    private String deliveryCity;

    @Column(name = "delivery_department", nullable = false, updatable = false)
    private String deliveryDepartment;

    @Column(name = "delivery_postal_code", length = 50, updatable = false)
    private String deliveryPostalCode;

    @Column(name = "delivery_phone", nullable = false, length = 50, updatable = false)
    private String deliveryPhone;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 100)
    private String transactionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 100)
    private List<OrderItemEntity> items = new ArrayList<>();

    public OrderEntity() {
    }

    public Long getId() { return id; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public OrderPaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(OrderPaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }

    public String getShippingMethod() { return shippingMethod; }
    public void setShippingMethod(String shippingMethod) { this.shippingMethod = shippingMethod; }

    public String getDeliveryRecipientName() { return deliveryRecipientName; }
    public void setDeliveryRecipientName(String value) { this.deliveryRecipientName = value; }

    public String getDeliveryStreet() { return deliveryStreet; }
    public void setDeliveryStreet(String value) { this.deliveryStreet = value; }

    public String getDeliveryCity() { return deliveryCity; }
    public void setDeliveryCity(String value) { this.deliveryCity = value; }

    public String getDeliveryDepartment() { return deliveryDepartment; }
    public void setDeliveryDepartment(String value) { this.deliveryDepartment = value; }

    public String getDeliveryPostalCode() { return deliveryPostalCode; }
    public void setDeliveryPostalCode(String value) { this.deliveryPostalCode = value; }

    public String getDeliveryPhone() { return deliveryPhone; }
    public void setDeliveryPhone(String value) { this.deliveryPhone = value; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<OrderItemEntity> getItems() { return items; }

    public void addItem(OrderItemEntity item) {
        items.add(item);
        item.setOrder(this);
    }
}
