package com.transformersas.marketplace.stock.dto;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductStatus;

/**
 * Una fila del inventario. Físico es lo que hay en bodega; reservado, lo que tienen apartado compras en curso;
 * disponible es la diferencia (lo que aún se puede vender).
 */
public record InventoryItemResponse(Long productId, String name, String category, ProductStatus status, int stock,
                                    int reserved, int available, int minStock, boolean lowStock) {

    public static InventoryItemResponse from(Product product, int reserved, boolean lowStock) {
        return new InventoryItemResponse(product.getId(), product.getName(), product.getCategory(),
                product.getStatus(), product.getStock(), reserved, product.getStock() - reserved,
                product.getMinStock(), lowStock);
    }
}
