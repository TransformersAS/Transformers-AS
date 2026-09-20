package com.transformersas.marketplace.product;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private Boolean active = true;

    // Tienda dueña del producto (CU-23). Por defecto la tienda 1 hasta que CU-14/CU-18 la asignen.
    @Column(name = "store_id", nullable = false)
    private Long storeId = 1L;

    /** Constructor previo a CU-23 (sin tienda): el producto queda en la tienda 1, igual que el valor por defecto. */
    public Product(Long id, String name, String description, BigDecimal price, Integer stock, String category,
                   Boolean active) {
        this(id, name, description, price, stock, category, active, 1L);
    }
}