package com.transformersas.marketplace.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
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

    // ---------- CU-14: publicación del vendedor ----------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductStatus status = ProductStatus.ACTIVE;

    @Column(name = "brand_id")
    private Long brandId;

    // El orden importa: la primera imagen es la principal.
    @ElementCollection
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 500)
    private List<String> imageUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "product_attribute_values", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "attribute_value_id", nullable = false)
    private Set<Long> attributeValueIds = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "product_variants", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "position")
    private List<ProductVariant> variants = new ArrayList<>();

    // ---------- CU-15: control de inventario ----------

    /** Nivel mínimo de stock; 0 significa "sin aviso". */
    @Column(name = "min_stock", nullable = false)
    private Integer minStock = 0;

    /** Ya se avisó de stock bajo; se apaga cuando el stock vuelve a superar el mínimo. */
    @Column(name = "low_stock_alerted", nullable = false)
    private Boolean lowStockAlerted = false;

    /** Constructor previo a CU-23 (sin tienda): el producto queda en la tienda 1, igual que el valor por defecto. */
    public Product(Long id, String name, String description, BigDecimal price, Integer stock, String category,
                   Boolean active) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.category = category;
        this.active = active;
    }

    /** Cambia el estado y mantiene "active" coherente: solo un producto ACTIVE se puede comprar. */
    public void changeStatus(ProductStatus newStatus) {
        this.status = newStatus;
        this.active = newStatus == ProductStatus.ACTIVE;
    }
}
