package com.transformersas.marketplace.promotions;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Un cupón de una tienda (CU-16). Sin productos elegidos aplica a toda la tienda. */
@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_purchase", precision = 19, scale = 2)
    private BigDecimal minPurchase;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "promotion_products", joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "product_id", nullable = false)
    private Set<Long> productIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Promotion(Long storeId) {
        this.storeId = storeId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** Vigente en este instante: activo y dentro de sus fechas. */
    public boolean isRunningAt(LocalDateTime moment) {
        return active && !moment.isBefore(startsAt) && moment.isBefore(endsAt);
    }

    /** Sin productos elegidos aplica a todos los de la tienda. */
    public boolean appliesTo(Long productId) {
        return productIds.isEmpty() || productIds.contains(productId);
    }

    /** Dos cupones chocan si se cruzan en fechas y comparten al menos un producto (o uno de ellos es para todos). */
    public boolean overlaps(Promotion other) {
        boolean datesCross = startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
        boolean productsCross = productIds.isEmpty() || other.productIds.isEmpty()
                || productIds.stream().anyMatch(other.productIds::contains);
        return datesCross && productsCross;
    }
}
