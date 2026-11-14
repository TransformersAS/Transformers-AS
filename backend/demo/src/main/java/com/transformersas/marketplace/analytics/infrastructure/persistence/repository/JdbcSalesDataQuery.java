package com.transformersas.marketplace.analytics.infrastructure.persistence.repository;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsFilter;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsOptions;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsOptions.ProductOption;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.Granularity;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.CategorySales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.ProductSales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;
import com.transformersas.marketplace.analytics.domain.model.SalesTotals;
import com.transformersas.marketplace.analytics.domain.repository.SalesDataQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Modelo de lectura de la analítica, calculado con agregaciones en MySQL: nunca trae las líneas de los pedidos a
 * memoria, así 10.000 pedidos se resumen en milisegundos (RNF-022). Lee pedidos, líneas, productos y reembolsos; no
 * escribe nada.
 *
 * <p>Reglas que comparten todas las consultas:
 * <ul>
 *   <li>Solo pedidos de la tienda, creados en el periodo y no cancelados.</li>
 *   <li>El descuento de un pedido se reparte entre sus líneas en proporción a su subtotal; así filtrar por producto
 *       no le carga a un producto el descuento entero.</li>
 *   <li>Un reembolso de reclamación o de devolución se atribuye al producto de esa reclamación o devolución (por su
 *       clave {@code claim-N} / {@code return-N}). Uno sin producto conocido solo cuenta cuando no hay filtros.</li>
 * </ul>
 */
@Repository
public class JdbcSalesDataQuery implements SalesDataQuery {

    /** Subtotal de todas las líneas de cada pedido del periodo, para repartir su descuento. */
    private static final String ORDER_TOTALS = """
            JOIN (SELECT oi2.order_id, SUM(oi2.subtotal) AS items_total
                  FROM order_items oi2 JOIN orders o2 ON o2.id = oi2.order_id
                  WHERE o2.store_id = :store AND o2.created_at >= :start AND o2.created_at < :end
                  GROUP BY oi2.order_id) t ON t.order_id = o.id""";

    private static final String VALID_LINES = """
            FROM orders o
            JOIN order_items oi ON oi.order_id = o.id
            %s
            LEFT JOIN products p ON p.id = oi.product_id
            WHERE o.store_id = :store AND o.status <> 'CANCELLED'
              AND o.created_at >= :start AND o.created_at < :end
              AND (:product IS NULL OR oi.product_id = :product)
              AND (:category IS NULL OR COALESCE(p.category, :noCategory) = :category)""".formatted(ORDER_TOTALS);

    private static final String LINE_DISCOUNT =
            "COALESCE(COALESCE(o.discount_amount, 0) * oi.subtotal / NULLIF(t.items_total, 0), 0)";
    private static final String LINE_SALES = "(oi.subtotal - " + LINE_DISCOUNT + ")";
    private static final String LINE_COST = "COALESCE(oi.unit_cost * oi.quantity, 0)";
    private static final String LINE_UNITS_WITHOUT_COST = "CASE WHEN oi.unit_cost IS NULL THEN oi.quantity ELSE 0 END";

    /** Reembolsos completados de pedidos válidos, con el producto al que se atribuyen (puede ser nulo). */
    private static final String REFUNDS = """
            FROM refunds r
            JOIN orders o ON o.id = r.order_id
            LEFT JOIN claims c ON r.idempotency_key LIKE 'claim-%%'
                 AND c.id = CAST(SUBSTRING(r.idempotency_key, 7) AS UNSIGNED)
            LEFT JOIN return_requests rr ON r.idempotency_key LIKE 'return-%%'
                 AND rr.id = CAST(SUBSTRING(r.idempotency_key, 8) AS UNSIGNED)
            LEFT JOIN products p ON p.id = COALESCE(c.product_id, rr.product_id)
            WHERE r.status = 'COMPLETED' AND o.store_id = :store AND o.status <> 'CANCELLED'
              AND o.created_at >= :start AND o.created_at < :end
              AND (:filtered = FALSE OR (COALESCE(c.product_id, rr.product_id) IS NOT NULL
                   AND (:product IS NULL OR COALESCE(c.product_id, rr.product_id) = :product)
                   AND (:category IS NULL OR COALESCE(p.category, :noCategory) = :category)))""".formatted();

    private final JdbcClient jdbc;

    public JdbcSalesDataQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SalesTotals totals(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter) {
        SalesTotals totals = bind(jdbc.sql("""
                        SELECT COALESCE(SUM(oi.subtotal), 0) AS gross,
                               COALESCE(SUM(%s), 0) AS discounts,
                               COALESCE(SUM(%s), 0) AS costs,
                               COUNT(DISTINCT o.id) AS orders,
                               COALESCE(SUM(oi.quantity), 0) AS units,
                               COALESCE(SUM(%s), 0) AS units_without_cost
                        %s""".formatted(LINE_DISCOUNT, LINE_COST, LINE_UNITS_WITHOUT_COST, VALID_LINES)),
                storeId, period, filter)
                .query((rs, row) -> new SalesTotals(rs.getBigDecimal("gross"), rs.getBigDecimal("discounts"),
                        BigDecimal.ZERO, rs.getBigDecimal("costs"), rs.getLong("orders"), rs.getLong("units"),
                        rs.getLong("units_without_cost")))
                .single();
        BigDecimal refunds = bind(jdbc.sql("SELECT COALESCE(SUM(r.amount), 0) " + REFUNDS), storeId, period, filter)
                .param("filtered", !filter.isEmpty())
                .query(BigDecimal.class).single();
        return totals.withRefunds(refunds);
    }

    @Override
    public List<ProductSales> topProducts(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter, int limit) {
        Map<Long, BigDecimal> refunds = bind(jdbc.sql("""
                        SELECT COALESCE(c.product_id, rr.product_id) AS product_id, SUM(r.amount) AS refunded
                        %s AND COALESCE(c.product_id, rr.product_id) IS NOT NULL
                        GROUP BY COALESCE(c.product_id, rr.product_id)""".formatted(REFUNDS)),
                storeId, period, filter)
                .param("filtered", !filter.isEmpty())
                .query((rs, row) -> Map.entry(rs.getLong("product_id"), rs.getBigDecimal("refunded")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return bind(jdbc.sql("""
                        SELECT oi.product_id,
                               COALESCE(MAX(p.name), MAX(oi.product_name)) AS name,
                               COALESCE(MAX(p.category), :noCategory) AS category,
                               SUM(oi.quantity) AS units,
                               SUM(%s) AS sales,
                               SUM(%s) AS costs,
                               SUM(%s) AS units_without_cost
                        %s
                        GROUP BY oi.product_id
                        ORDER BY units DESC, sales DESC, oi.product_id
                        LIMIT :limit""".formatted(LINE_SALES, LINE_COST, LINE_UNITS_WITHOUT_COST, VALID_LINES)),
                storeId, period, filter)
                .param("limit", limit)
                .query((rs, row) -> {
                    long productId = rs.getLong("product_id");
                    BigDecimal sales = money(rs.getBigDecimal("sales"));
                    BigDecimal refunded = money(refunds.get(productId));
                    BigDecimal costs = money(rs.getBigDecimal("costs"));
                    return new ProductSales(productId, rs.getString("name"), rs.getString("category"),
                            rs.getLong("units"), sales, refunded, costs, sales.subtract(refunded).subtract(costs),
                            rs.getLong("units_without_cost"));
                })
                .list();
    }

    @Override
    public List<CategorySales> categories(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter) {
        return bind(jdbc.sql("""
                        SELECT COALESCE(p.category, :noCategory) AS category_name, SUM(oi.quantity) AS units,
                               SUM(%s) AS sales
                        %s
                        GROUP BY category_name
                        ORDER BY sales DESC, category_name""".formatted(LINE_SALES, VALID_LINES)),
                storeId, period, filter)
                .query((rs, row) -> new CategorySales(rs.getString("category_name"), rs.getLong("units"),
                        money(rs.getBigDecimal("sales"))))
                .list();
    }

    @Override
    public List<SeriesPoint> series(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter,
                                    Granularity granularity) {
        return bind(jdbc.sql("""
                        SELECT %s AS bucket, SUM(%s) AS sales, SUM(%s) AS costs, COUNT(DISTINCT o.id) AS orders
                        %s
                        GROUP BY bucket
                        ORDER BY bucket""".formatted(bucket(granularity), LINE_SALES, LINE_COST, VALID_LINES)),
                storeId, period, filter)
                .query((rs, row) -> new SeriesPoint(rs.getDate("bucket").toLocalDate(),
                        money(rs.getBigDecimal("sales")), money(rs.getBigDecimal("costs")), rs.getLong("orders")))
                .list();
    }

    @Override
    public Optional<String> productName(Long storeId, Long productId) {
        return jdbc.sql("SELECT name FROM products WHERE id = ? AND store_id = ?")
                .params(productId, storeId).query(String.class).optional();
    }

    @Override
    public AnalyticsOptions options(Long storeId) {
        List<ProductOption> products = jdbc.sql("""
                        SELECT id, name, category, unit_cost IS NOT NULL AS has_cost
                        FROM products WHERE store_id = ? ORDER BY name, id""")
                .param(storeId)
                .query((rs, row) -> new ProductOption(rs.getLong("id"), rs.getString("name"),
                        rs.getString("category"), rs.getBoolean("has_cost")))
                .list();
        List<String> categories = products.stream().map(ProductOption::category).distinct().sorted().toList();
        return new AnalyticsOptions(products, categories);
    }

    private static String bucket(Granularity granularity) {
        return switch (granularity) {
            case DAY -> "DATE(o.created_at)";
            case WEEK -> "DATE_SUB(DATE(o.created_at), INTERVAL WEEKDAY(o.created_at) DAY)";
            case MONTH -> "MAKEDATE(YEAR(o.created_at), 1) + INTERVAL (MONTH(o.created_at) - 1) MONTH";
        };
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, Long storeId, AnalyticsPeriod period,
                                                 AnalyticsFilter filter) {
        return sql.param("store", storeId)
                .param("start", period.startInclusive())
                .param("end", period.endExclusive())
                .param("product", filter.productId())
                .param("category", filter.category())
                .param("noCategory", AnalyticsFilter.NO_CATEGORY);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
