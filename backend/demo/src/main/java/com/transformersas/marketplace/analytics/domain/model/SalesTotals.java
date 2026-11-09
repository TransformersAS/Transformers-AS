package com.transformersas.marketplace.analytics.domain.model;

import java.math.BigDecimal;

/**
 * Sumas crudas de un periodo, tal como salen de los pedidos. grossSales es el precio por cantidad de las líneas;
 * discounts, la parte de los descuentos del pedido que corresponde a esas líneas; refunds, los reembolsos completados
 * atribuibles a ellas; costs, el costo de las unidades con costo conocido (unitsWithoutCost no tienen).
 */
public record SalesTotals(BigDecimal grossSales, BigDecimal discounts, BigDecimal refunds, BigDecimal costs,
                          long orders, long units, long unitsWithoutCost) {

    public static SalesTotals empty() {
        return new SalesTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0);
    }

    public SalesTotals withRefunds(BigDecimal amount) {
        return new SalesTotals(grossSales, discounts, amount, costs, orders, units, unitsWithoutCost);
    }
}
