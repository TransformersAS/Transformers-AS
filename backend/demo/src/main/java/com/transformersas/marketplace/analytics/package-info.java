/**
 * CU-26 Analizar ventas y rentabilidad: indicadores de la tienda del vendedor por periodo, producto o categoría,
 * comparación entre periodos, gráficas y exportación a Excel.
 *
 * <p>Solo lee: la consulta y la exportación nunca modifican pedidos, productos ni inventario (postcondición 7). Las
 * ventas salen de los pedidos no cancelados de la tienda; los reembolsos completados se descuentan del pedido al que
 * pertenecen; los costos usan el costo unitario copiado en cada línea al comprar.
 */
package com.transformersas.marketplace.analytics;
