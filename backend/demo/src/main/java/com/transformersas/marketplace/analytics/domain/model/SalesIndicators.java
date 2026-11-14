package com.transformersas.marketplace.analytics.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Indicadores de un periodo (RF-124), en pesos con dos decimales.
 *
 * <ul>
 *   <li>sales: ventas, el valor de las líneas menos los descuentos que les corresponden. El envío no es una venta.</li>
 *   <li>netRevenue: ingresos netos, las ventas menos los reembolsos completados.</li>
 *   <li>profit: ganancia, los ingresos netos menos el costo de las unidades vendidas.</li>
 *   <li>marginPercent: ganancia sobre ingresos netos; nulo si no hay ingresos netos positivos.</li>
 *   <li>averageTicket: ventas por pedido; cero sin pedidos.</li>
 * </ul>
 *
 * Si alguna unidad no tiene costo registrado (costComplete false), costs y profit solo cubren las que sí lo tienen.
 */
public record SalesIndicators(BigDecimal sales, BigDecimal discounts, BigDecimal refunds, BigDecimal netRevenue,
                              BigDecimal costs, BigDecimal profit, BigDecimal marginPercent, long orders, long units,
                              BigDecimal averageTicket, long unitsWithoutCost) {

    public static SalesIndicators from(SalesTotals totals) {
        BigDecimal discounts = money(totals.discounts());
        BigDecimal sales = money(totals.grossSales()).subtract(discounts);
        BigDecimal refunds = money(totals.refunds());
        BigDecimal netRevenue = sales.subtract(refunds);
        BigDecimal costs = money(totals.costs());
        BigDecimal profit = netRevenue.subtract(costs);
        BigDecimal margin = netRevenue.signum() > 0
                ? profit.multiply(BigDecimal.valueOf(100)).divide(netRevenue, 1, RoundingMode.HALF_UP) : null;
        BigDecimal ticket = totals.orders() == 0 ? money(BigDecimal.ZERO)
                : sales.divide(BigDecimal.valueOf(totals.orders()), 2, RoundingMode.HALF_UP);
        return new SalesIndicators(sales, discounts, refunds, netRevenue, costs, profit, margin, totals.orders(),
                totals.units(), ticket, totals.unitsWithoutCost());
    }

    public boolean hasData() {
        return orders > 0;
    }

    public boolean costComplete() {
        return unitsWithoutCost == 0;
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
