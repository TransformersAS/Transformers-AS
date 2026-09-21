/** Casos de uso de inventario. */
package com.transformersas.marketplace.inventory.application.usecase;

import com.transformersas.marketplace.inventory.domain.repository.StockRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;

/** Contrato para que otros módulos consulten existencias sin tocar la tabla de productos (RF-112, RF-113). */
@Service
public class GetStockLevelsUseCase {

    private final StockRepository stock;

    public GetStockLevelsUseCase(StockRepository stock) {
        this.stock = stock;
    }

    /** Existencias actuales por producto; los productos inexistentes no aparecen en el resultado. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> execute(Collection<Long> productIds) {
        return productIds.isEmpty() ? Map.of() : stock.findStock(productIds);
    }
}
