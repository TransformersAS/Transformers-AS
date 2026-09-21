package com.transformersas.marketplace.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Una línea del historial de inventario: qué movimiento manual hizo el vendedor, cuánto cambió y cómo quedó el stock. */
@Entity
@Table(name = "stock_movements")
@Getter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StockMovementType type;

    /** Unidades que sumó o restó el movimiento; en un ajuste puede ser negativa. */
    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter;

    @Column(length = 255)
    private String reason;

    @Column(name = "actor_account_id", nullable = false)
    private Long actorAccountId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public StockMovement(Long productId, StockMovementType type, Integer quantity, Integer stockAfter, String reason,
                         Long actorAccountId) {
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.stockAfter = stockAfter;
        this.reason = reason;
        this.actorAccountId = actorAccountId;
        this.createdAt = LocalDateTime.now();
    }
}
