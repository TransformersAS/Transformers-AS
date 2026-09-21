package com.transformersas.marketplace.inventory.application.usecase;

import com.transformersas.marketplace.inventory.domain.repository.StockAdjustmentRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devuelve unidades al inventario (cancelaciones de CU-23 y CU-11, devoluciones de CU-19). Exige una transacción
 * abierta (MANDATORY): el movimiento de stock debe confirmarse o revertirse junto con el cambio de negocio que lo
 * origina (RNF-015). El incremento es atómico en BD (stock = stock + n).
 */
@Service
public class RestoreStockUseCase {

    private final StockAdjustmentRepository stock;

    public RestoreStockUseCase(StockAdjustmentRepository stock) {
        this.stock = stock;
    }

    /** Devuelve true si se repuso; false si el producto ya no existe (no hay a dónde devolver las unidades). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean execute(Long productId, int quantity) {
        if (quantity <= 0) {
            throw BusinessException.invalid("INVALID_QUANTITY", "La cantidad a reponer debe ser positiva");
        }
        return stock.increase(productId, quantity);
    }
}
