package com.transformersas.marketplace.product;

import com.transformersas.marketplace.catalog.BrandRepository;
import com.transformersas.marketplace.product.dto.ProductDetailResponse;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductDetailService {
    private final ProductService catalog;
    private final ProductRepository products;
    private final BrandRepository brands;
    private final StoreRepository stores;
    private final JdbcTemplate jdbc;

    public ProductDetailService(ProductService catalog, ProductRepository products, BrandRepository brands,
                                StoreRepository stores, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.products = products;
        this.brands = brands;
        this.stores = stores;
        this.jdbc = jdbc;
    }

    public ProductDetailResponse getById(Long id) {
        // Conserva la validacion de visibilidad y moderacion del catalogo.
        var summary = catalog.getProductById(id);
        var product = products.findById(id).orElseThrow();
        String brand = product.getBrandId() == null ? null
                : brands.findById(product.getBrandId()).map(b -> b.getName()).orElse(null);
        var store = stores.findById(product.getStoreId())
                .map(s -> new ProductDetailResponse.StoreSummary(s.id(), s.profile().name(), s.profile().description()))
                .orElse(null);
        var specifications = jdbc.query("""
                SELECT a.name, av.value_text FROM product_attribute_values pav
                JOIN attribute_values av ON av.id = pav.attribute_value_id
                JOIN attributes a ON a.id = av.attribute_id
                WHERE pav.product_id = ? ORDER BY a.name, av.value_text
                """, (rs, row) -> new ProductDetailResponse.Specification(rs.getString(1), rs.getString(2)), id);
        return new ProductDetailResponse(summary.id(), summary.name(), summary.description(), summary.price(),
                summary.stock(), summary.category(), summary.active(), brand, store,
                java.util.List.copyOf(product.getImageUrls()), specifications,
                product.getVariants().stream().map(v -> new ProductDetailResponse.Variant(
                        v.getName(), v.getPrice(), v.getStock())).toList());
    }
}
