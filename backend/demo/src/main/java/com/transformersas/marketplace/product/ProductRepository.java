package com.transformersas.marketplace.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.transformersas.marketplace.product.dto.ProductResponse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCategoryIgnoreCase(String category);

    boolean existsByBrandId(Long brandId);

    /** ¿Algún producto usa este valor de atributo (por ejemplo, Color: Rojo)? */
    @Query("select count(p) > 0 from Product p join p.attributeValueIds v where v = :valueId")
    boolean existsByAttributeValueId(@Param("valueId") Long valueId);

    /**
     * Catálogo que ven los compradores, filtrado en MySQL: sin borradores ni retirados, sin publicaciones ocultas por
     * moderación y sin tiendas que no pueden vender. Devuelve solo las columnas del listado (no carga la entidad ni
     * sus colecciones). Las dos listas de exclusión no pueden ir vacías: quien llama pasa un id imposible.
     */
    @Query("""
            select new com.transformersas.marketplace.product.dto.ProductResponse(
                p.id, p.name, p.description, p.price, p.stock, p.category, p.active)
            from Product p
            where p.status in :statuses and p.id not in :hiddenIds and p.storeId not in :closedStoreIds
            order by p.id
            """)
    List<ProductResponse> findCatalog(@Param("statuses") Collection<ProductStatus> statuses,
                                      @Param("hiddenIds") Collection<Long> hiddenIds,
                                      @Param("closedStoreIds") Collection<Long> closedStoreIds);

    List<Product> findByStoreId(Long storeId);

    Optional<Product> findByIdAndStoreId(Long id, Long storeId);

    /** CU-15: igual que la anterior, pero bloquea la fila para que dos movimientos de inventario no se pisen. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.storeId = :storeId")
    Optional<Product> findForUpdate(@Param("id") Long id, @Param("storeId") Long storeId);

    /** CU-15: productos que tienen un mínimo configurado o una alerta pendiente de apagar (los que hay que revisar). */
    List<Product> findByMinStockGreaterThanOrLowStockAlertedTrue(Integer minStock);

    // CU-02 - productos disponibles para recomendaciones
    List<Product> findByActiveTrueAndStockGreaterThan(Integer stock);
}