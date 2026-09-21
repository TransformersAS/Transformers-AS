package com.transformersas.marketplace.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCategoryIgnoreCase(String category);

    boolean existsByBrandId(Long brandId);

    /** ¿Algún producto usa este valor de atributo (por ejemplo, Color: Rojo)? */
    @Query("select count(p) > 0 from Product p join p.attributeValueIds v where v = :valueId")
    boolean existsByAttributeValueId(@Param("valueId") Long valueId);

    List<Product> findByStoreId(Long storeId);

    Optional<Product> findByIdAndStoreId(Long id, Long storeId);

    // CU-02 - productos disponibles para recomendaciones
    List<Product> findByActiveTrueAndStockGreaterThan(Integer stock);
}