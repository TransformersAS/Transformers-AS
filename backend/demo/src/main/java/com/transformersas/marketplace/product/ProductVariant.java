package com.transformersas.marketplace.product;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Variante de un producto (por ejemplo, "Talla M") con su propio precio e inventario. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariant {

    private String name;

    private BigDecimal price;

    private Integer stock;
}
