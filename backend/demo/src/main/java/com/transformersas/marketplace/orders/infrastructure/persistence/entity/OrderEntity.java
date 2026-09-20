/** Representaciones JPA privadas de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.entity;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable only for historical orders whose owner is unknown.
    @Column(name = "account_id")
    private Long accountId;

    public Long getAccountId() { return accountId; }

    public void setAccountId(Long accountId) { this.accountId = accountId; }


    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;


    @Column(
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal total;


    @Column(
            name = "address_id",
            nullable = false
    )
    private Long addressId;


    @Column(
            name = "shipping_method",
            nullable = false,
            length = 30
    )
    private String shippingMethod;


    @Column(
            name = "transaction_id",
            nullable = false,
            unique = true,
            length = 100
    )
    private String transactionId;


    @Column(
            name = "created_at",
            nullable = false
    )
    private LocalDateTime createdAt;


    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItemEntity> items =
            new ArrayList<>();


    public OrderEntity() {
    }


    public Long getId() {
        return id;
    }


    public OrderStatus getStatus() {
        return status;
    }


    public void setStatus(
            OrderStatus status
    ) {
        this.status = status;
    }


    public BigDecimal getTotal() {
        return total;
    }


    public void setTotal(
            BigDecimal total
    ) {
        this.total = total;
    }


    public Long getAddressId() {
        return addressId;
    }


    public void setAddressId(
            Long addressId
    ) {
        this.addressId = addressId;
    }


    public String getShippingMethod() {
        return shippingMethod;
    }


    public void setShippingMethod(
            String shippingMethod
    ) {
        this.shippingMethod = shippingMethod;
    }


    public String getTransactionId() {
        return transactionId;
    }


    public void setTransactionId(
            String transactionId
    ) {
        this.transactionId = transactionId;
    }


    public LocalDateTime getCreatedAt() {
        return createdAt;
    }


    public void setCreatedAt(
            LocalDateTime createdAt
    ) {
        this.createdAt = createdAt;
    }


    public List<OrderItemEntity> getItems() {
        return items;
    }


    public void addItem(
            OrderItemEntity item
    ) {

        items.add(item);

        item.setOrder(this);
    }
}