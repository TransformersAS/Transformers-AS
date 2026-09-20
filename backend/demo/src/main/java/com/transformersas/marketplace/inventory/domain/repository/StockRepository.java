package com.transformersas.marketplace.inventory.domain.repository;

import java.util.Collection;
import java.util.Map;

/** Puerto de existencias físicas por producto. */
public interface StockRepository {

    /** Existencias actuales de los productos indicados. Los productos que ya no existen no aparecen en el mapa. */
    Map<Long, Integer> findStock(Collection<Long> productIds);
}
