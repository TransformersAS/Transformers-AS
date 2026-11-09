package com.transformersas.marketplace.analytics;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-26 de punta a punta, con números calculados a mano. Escenario (periodo principal: los últimos 10 días):
 * <ul>
 *   <li>O1, hace 3 días, entregado: 2 × Lámpara (100, costo 60) + 1 × Camiseta (50, sin costo), descuento 25.
 *       Reclamación resuelta con reembolso completado de 30 sobre la lámpara.</li>
 *   <li>O2, hace 2 días, confirmado: 1 × Lámpara. Un reembolso pendiente de 15 que no cuenta.</li>
 *   <li>O3 cancelado, O5 de otra tienda y O4 de hace 20 días (fuera del periodo) no cuentan.</li>
 * </ul>
 * Ventas = 350 − 25 = 325; reembolsos 30; ingresos netos 295; costos 3 × 60 = 180; ganancia 115.
 */
class SellerAnalyticsIntegrationTests extends AbstractIntegrationTest {

    private static final String API = "/api/seller/analytics";

    private Session seller;
    private long buyer;
    private long lamp;
    private long shirt;
    private long otherStoreProduct;
    private long firstOrder;
    private LocalDate today;

    private record Line(long productId, int quantity, String price, String cost) {
    }

    @BeforeEach
    void scenario() throws Exception {
        today = LocalDate.now();
        seller = sellerOfStore("analitica@example.com", 1);
        buyer = createAccount("comprador.analitica@example.com", "COMPRADOR");
        seedStore(2, "Otra tienda");
        lamp = product(1, "Lampara", "Hogar", "100", "60");
        shirt = product(1, "Camiseta", "Ropa", "50", null);
        otherStoreProduct = product(2, "Ajeno", "Hogar", "70", "10");

        firstOrder = order(1, "DELIVERED", daysAgo(3), "25", new Line(lamp, 2, "100", "60"),
                new Line(shirt, 1, "50", null));
        long second = order(1, "CONFIRMED", daysAgo(2), null, new Line(lamp, 1, "100", "60"));
        long cancelled = order(1, "CANCELLED", daysAgo(2), null, new Line(lamp, 5, "100", "60"));
        order(1, "DELIVERED", daysAgo(20), null, new Line(shirt, 2, "50", null));
        order(2, "DELIVERED", daysAgo(2), null, new Line(otherStoreProduct, 3, "70", "10"));

        refundedClaim(firstOrder, lamp, "30", "COMPLETED");
        refundedClaim(second, lamp, "15", "PENDING");
        refund(cancelled, "500", "order-cancel-" + cancelled, "COMPLETED");
    }

    // ---------- RF-124: indicadores ----------

    @Test
    void indicatorsCountOnlyValidOrdersOfTheStoreAndDiscountRefunds() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Tienda principal"))
                .andExpect(jsonPath("$.current.hasData").value(true))
                .andExpect(jsonPath("$.current.indicators.sales").value(325.00))
                .andExpect(jsonPath("$.current.indicators.discounts").value(25.00))
                .andExpect(jsonPath("$.current.indicators.refunds").value(30.00))
                .andExpect(jsonPath("$.current.indicators.netRevenue").value(295.00))
                .andExpect(jsonPath("$.current.indicators.costs").value(180.00))
                .andExpect(jsonPath("$.current.indicators.profit").value(115.00))
                .andExpect(jsonPath("$.current.indicators.marginPercent").value(39.0))
                .andExpect(jsonPath("$.current.indicators.orders").value(2))
                .andExpect(jsonPath("$.current.indicators.units").value(4))
                .andExpect(jsonPath("$.current.indicators.averageTicket").value(162.50))
                .andExpect(jsonPath("$.current.indicators.unitsWithoutCost").value(1))
                .andExpect(jsonPath("$.current.indicators.costComplete").value(false))
                .andExpect(jsonPath("$.comparison").doesNotExist());
    }

    @Test
    void bestSellersAreRankedByUnitsWithTheirOwnRefundsAndCosts() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.current.topProducts", hasSize(2)))
                .andExpect(jsonPath("$.current.topProducts[0].name").value("Lampara"))
                .andExpect(jsonPath("$.current.topProducts[0].units").value(3))
                .andExpect(jsonPath("$.current.topProducts[0].sales").value(280.00))
                .andExpect(jsonPath("$.current.topProducts[0].refunds").value(30.00))
                .andExpect(jsonPath("$.current.topProducts[0].costs").value(180.00))
                .andExpect(jsonPath("$.current.topProducts[0].profit").value(70.00))
                .andExpect(jsonPath("$.current.topProducts[1].name").value("Camiseta"))
                .andExpect(jsonPath("$.current.topProducts[1].unitsWithoutCost").value(1))
                .andExpect(jsonPath("$.current.categories[0].category").value("Hogar"))
                .andExpect(jsonPath("$.current.categories[0].sales").value(280.00))
                .andExpect(jsonPath("$.current.categories[1].category").value("Ropa"))
                .andExpect(jsonPath("$.current.categories[1].sales").value(45.00));
    }

    @Test
    void dailySeriesCoversEveryDayOfThePeriodEvenWithoutSales() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.current.series", hasSize(11)))
                .andExpect(jsonPath("$.current.series[7].start").value(daysAgo(3).toLocalDate().toString()))
                .andExpect(jsonPath("$.current.series[7].sales").value(225.00))
                .andExpect(jsonPath("$.current.series[8].sales").value(100.00))
                .andExpect(jsonPath("$.current.series[0].sales").value(0.00))
                .andExpect(jsonPath("$.current.series[0].orders").value(0));
    }

    // ---------- RF-197: filtros ----------

    @Test
    void productFilterKeepsOnlyItsShareOfTheDiscountAndItsRefunds() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("productId", String.valueOf(lamp)))
                .andExpect(jsonPath("$.productName").value("Lampara"))
                .andExpect(jsonPath("$.current.indicators.discounts").value(20.00))
                .andExpect(jsonPath("$.current.indicators.sales").value(280.00))
                .andExpect(jsonPath("$.current.indicators.refunds").value(30.00))
                .andExpect(jsonPath("$.current.indicators.profit").value(70.00))
                .andExpect(jsonPath("$.current.indicators.orders").value(2))
                .andExpect(jsonPath("$.current.indicators.costComplete").value(true));
    }

    @Test
    void categoryFilterLeavesOutRefundsOfOtherCategories() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("category", "Ropa"))
                .andExpect(jsonPath("$.category").value("Ropa"))
                .andExpect(jsonPath("$.current.indicators.sales").value(45.00))
                .andExpect(jsonPath("$.current.indicators.refunds").value(0.00))
                .andExpect(jsonPath("$.current.indicators.orders").value(1));
    }

    @Test
    void filterWithoutSalesAnswersWithZerosAndEmptyRankings() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("category", "Juguetes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.hasData").value(false))
                .andExpect(jsonPath("$.current.indicators.sales").value(0.00))
                .andExpect(jsonPath("$.current.indicators.averageTicket").value(0.00))
                .andExpect(jsonPath("$.current.indicators.marginPercent").doesNotExist())
                .andExpect(jsonPath("$.current.topProducts", hasSize(0)));
    }

    @Test
    void productOfAnotherStoreLooksLikeItDoesNotExist() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("productId", String.valueOf(otherStoreProduct)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // ---------- RF-125: comparación ----------

    @Test
    void comparisonShowsBothPeriodsTheirDifferenceAndPercentage() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("compareFrom", daysAgo(30).toLocalDate().toString())
                .param("compareTo", daysAgo(11).toLocalDate().toString()))
                .andExpect(jsonPath("$.comparison.hasData").value(true))
                .andExpect(jsonPath("$.comparison.indicators.sales").value(100.00))
                .andExpect(jsonPath("$.comparison.indicators.unitsWithoutCost").value(2))
                .andExpect(jsonPath("$.comparisons[0].indicator").value("sales"))
                .andExpect(jsonPath("$.comparisons[0].current").value(325.00))
                .andExpect(jsonPath("$.comparisons[0].previous").value(100.00))
                .andExpect(jsonPath("$.comparisons[0].difference").value(225.00))
                .andExpect(jsonPath("$.comparisons[0].percentChange").value(225.0))
                .andExpect(jsonPath("$.comparisons[0].percentAvailable").value(true));
    }

    @Test
    void comparisonAgainstAnEmptyPeriodKeepsValuesButNoPercentage() throws Exception {
        perform(seller, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString())
                .param("compareFrom", daysAgo(200).toLocalDate().toString())
                .param("compareTo", daysAgo(190).toLocalDate().toString()))
                .andExpect(jsonPath("$.current.hasData").value(true))
                .andExpect(jsonPath("$.comparison.hasData").value(false))
                .andExpect(jsonPath("$.comparisons[0].difference").value(325.00))
                .andExpect(jsonPath("$.comparisons[0].percentChange").doesNotExist())
                .andExpect(jsonPath("$.comparisons[0].percentAvailable").value(false));
    }

    // ---------- A2: periodos inválidos ----------

    @Test
    void invalidPeriodsAreRejectedNamingTheFieldToFix() throws Exception {
        perform(seller, get(API).param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"))
                .andExpect(jsonPath("$.details.field").value("from"));
        perform(seller, get(API).param("to", today.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("from"));
        perform(seller, get(API).param("from", today.minusDays(800).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("to"));
        perform(seller, get(API).param("from", today.plusDays(1).toString()).param("to", today.plusDays(2).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        perform(seller, get(API).param("from", today.toString()).param("to", today.toString())
                .param("compareFrom", today.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON_PERIOD"))
                .andExpect(jsonPath("$.details.field").value("to"));
    }

    // ---------- Postcondición 4: costos históricos ----------

    @Test
    void costIsCopiedAtPurchaseAndLaterChangesDoNotRewriteHistory() throws Exception {
        Session shopper = sessionWithRole("compra.costo@example.com", "COMPRADOR");
        long orderId = checkoutOrder(shopper, lamp, 1);
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM order_items WHERE order_id = ?",
                java.math.BigDecimal.class, orderId)).isEqualByComparingTo("60");

        jdbc.update("UPDATE products SET unit_cost = 99 WHERE id = ?", lamp);
        perform(seller, get(API).param("from", today.toString()).param("to", today.toString())
                .param("productId", String.valueOf(lamp)))
                .andExpect(jsonPath("$.current.indicators.costs").value(60.00));

        // El costo es del vendedor: el comprador no lo ve en su pedido.
        perform(shopper, get("/api/orders/" + orderId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.items[0].unitCost").doesNotExist());
    }

    @Test
    void sellerRegistersTheInternalCostOfAProduct() throws Exception {
        // CU-14 solo acepta categorías activas del catálogo; esta prueba crea la suya y la retira al terminar.
        jdbc.update("INSERT INTO categories(name, active) VALUES ('Ropa analitica', TRUE)");
        try {
            perform(seller, put("/api/seller/products/" + shirt).contentType("application/json").content("""
                    {"name":"Camiseta","price":50,"stock":10,"category":"Ropa analitica","unitCost":21.5}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unitCost").value(21.5));
            perform(seller, put("/api/seller/products/" + shirt).contentType("application/json").content("""
                    {"name":"Camiseta","price":50,"stock":10,"category":"Ropa analitica","unitCost":-1}"""))
                    .andExpect(status().isBadRequest());
            // El catálogo público nunca muestra el costo.
            mvc.perform(get("/api/products/" + shirt)).andExpect(jsonPath("$.unitCost").doesNotExist());
        } finally {
            jdbc.update("DELETE FROM categories WHERE name = 'Ropa analitica'");
        }
    }

    // ---------- Opciones y exportación ----------

    @Test
    void optionsListOnlyTheSellerProductsAndWhichHaveCost() throws Exception {
        perform(seller, get(API + "/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products", hasSize(2)))
                .andExpect(jsonPath("$.products[0].name").value("Camiseta"))
                .andExpect(jsonPath("$.products[0].hasCost").value(false))
                .andExpect(jsonPath("$.products[1].hasCost").value(true))
                .andExpect(jsonPath("$.categories[0]").value("Hogar"))
                .andExpect(jsonPath("$.categories[1]").value("Ropa"));
    }

    @Test
    void excelExportContainsPeriodsFiltersAndIndicatorsAndChangesNothing() throws Exception {
        int orders = count("orders");
        int products = count("products");
        byte[] file = perform(seller, get(API + "/export").param("from", daysAgo(10).toLocalDate().toString())
                .param("to", today.toString()).param("compareFrom", daysAgo(30).toLocalDate().toString())
                .param("compareTo", daysAgo(11).toLocalDate().toString()).param("category", "Hogar"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "analitica-ventas-" + daysAgo(10).toLocalDate() + "_" + today + ".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (var book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(book.getSheetName(0)).isEqualTo("Resumen");
            Sheet summary = book.getSheet("Resumen");
            assertThat(text(summary, "Tienda")).isEqualTo("Tienda principal");
            assertThat(text(summary, "Categoría")).isEqualTo("Hogar");
            assertThat(text(summary, "Comparación")).startsWith(daysAgo(30).toLocalDate().toString());
            Row sales = row(summary, "Ventas");
            assertThat(sales.getCell(1).getNumericCellValue()).isEqualTo(280.0);
            assertThat(sales.getCell(2).getNumericCellValue()).isEqualTo(0.0);
            assertThat(sales.getCell(4).getStringCellValue()).startsWith("No calculable");
            assertThat(book.getSheet("Más vendidos").getRow(2).getCell(1).getStringCellValue()).isEqualTo("Lampara");
            assertThat(book.getSheet("Serie").getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo(daysAgo(10).toLocalDate().toString());
        }
        assertThat(count("orders")).isEqualTo(orders);
        assertThat(count("products")).isEqualTo(products);
    }

    // ---------- RNF-003: solo el vendedor y solo su tienda ----------

    @Test
    void onlyTheSellerRoleCanUseTheAnalytics() throws Exception {
        Session shopper = sessionWithRole("curioso@example.com", "COMPRADOR");
        perform(shopper, get(API).param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get(API).param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/export").param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherSellerOnlySeesTheirOwnStore() throws Exception {
        Session neighbour = sellerOfStore("vecino@example.com", 2);
        perform(neighbour, get(API).param("from", daysAgo(10).toLocalDate().toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.storeName").value("Otra tienda"))
                .andExpect(jsonPath("$.current.indicators.sales").value(210.00))
                .andExpect(jsonPath("$.current.indicators.orders").value(1));
    }

    // ---------- RNF-022: 10.000 pedidos en menos de 5 segundos ----------

    @Test
    void tenThousandOrdersAreAnalyzedAndComparedInUnderFiveSeconds() throws Exception {
        long address = seedAddress();
        // 10 × 10 × 10 × 10 = 10.000 filas; i va de 1 a 10.000. Uno de cada diez queda cancelado.
        String digits = "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.update("""
                INSERT INTO orders(status, payment_status, total, store_id, address_id, shipping_method,
                                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                                   delivery_postal_code, delivery_phone, transaction_id, created_at)
                SELECT IF(n.i %% 10 = 0, 'CANCELLED', 'DELIVERED'), 'APPROVED', 110, 1, ?, 'STANDARD', 'Ana', 'Calle 1',
                       'Bogotá', 'Cundinamarca', '110111', '3000000000', CONCAT('carga-', n.i),
                       ? - INTERVAL (n.i %% 360) DAY
                FROM (SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 1 AS i
                      FROM %1$s a CROSS JOIN %1$s b CROSS JOIN %1$s c CROSS JOIN %1$s e) n""".formatted(digits),
                address, LocalDateTime.now());
        jdbc.update("""
                INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal, unit_cost)
                SELECT id, IF(id % 2 = 0, ?, ?), 'Carga', 1, 100, 100, IF(id % 2 = 0, 60, NULL)
                FROM orders WHERE transaction_id LIKE 'carga-%'""", lamp, shirt);

        long started = System.nanoTime();
        perform(seller, get(API).param("from", today.minusDays(364).toString()).param("to", today.toString())
                .param("compareFrom", today.minusDays(729).toString()).param("compareTo", today.minusDays(365).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("WEEK"))
                // 9.000 válidos de la carga más O1, O2 y O4 del escenario, que también caen en el último año.
                .andExpect(jsonPath("$.current.indicators.orders").value(9003));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).as("RNF-022: analítica de 10.000 pedidos en %d ms", millis).isLessThan(5_000);
    }

    // ---------- Datos de prueba ----------

    private LocalDateTime daysAgo(int days) {
        return today.minusDays(days).atTime(10, 0);
    }

    private long product(long storeId, String name, String category, String price, String cost) {
        jdbc.update("""
                INSERT INTO products(name, price, stock, category, active, status, store_id, unit_cost)
                VALUES (?, ?, 50, ?, TRUE, 'ACTIVE', ?, ?)""", name, price, category, storeId, cost);
        return jdbc.queryForObject("SELECT MAX(id) FROM products", Long.class);
    }

    private long order(long storeId, String status, LocalDateTime createdAt, String discount, Line... lines) {
        long address = seedAddress();
        String transaction = "analitica-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO orders(status, payment_status, total, store_id, address_id, shipping_method,
                                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                                   delivery_postal_code, delivery_phone, transaction_id, created_at, discount_amount,
                                   shipping_cost, account_id)
                SELECT ?, 'APPROVED', 0, ?, id, 'STANDARD', recipient_name, street, city, department, postal_code,
                       phone, ?, ?, ?, 10000, ?
                FROM addresses WHERE id = ?""", status, storeId, transaction, createdAt, discount, buyer, address);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE transaction_id = ?", Long.class, transaction);
        for (Line line : lines) {
            jdbc.update("""
                    INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal, unit_cost)
                    SELECT ?, id, name, ?, ?, ? * ?, ? FROM products WHERE id = ?""",
                    orderId, line.quantity(), line.price(), line.price(), line.quantity(), line.cost(),
                    line.productId());
        }
        return orderId;
    }

    /** Reclamación que soporte resolvió reembolsando; el reembolso queda con la clave que usa ClaimService. */
    private void refundedClaim(long orderId, long productId, String amount, String refundStatus) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO claims(order_id, product_id, product_name, item_total, buyer_account_id, store_id,
                                   description, status, resolution, refund_amount, created_at, updated_at)
                VALUES (?, ?, 'Reclamado', ?, ?, 1, 'Llegó dañado', 'RESOLVED', 'REFUND_GRANTED', ?, ?, ?)""",
                orderId, productId, amount, buyer, amount, now, now);
        long claim = jdbc.queryForObject("SELECT MAX(id) FROM claims", Long.class);
        refund(orderId, amount, "claim-" + claim, refundStatus);
    }

    private void refund(long orderId, String amount, String key, String refundStatus) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO refunds(order_id, amount, idempotency_key, status, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'prueba', ?, ?)""", orderId, amount, key, refundStatus, now, now);
    }

    private static Row row(Sheet sheet, String label) {
        return StreamSupport.stream(sheet.spliterator(), false)
                .filter(row -> row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue()))
                .findFirst().orElseThrow(() -> new AssertionError("Sin fila " + label));
    }

    private static String text(Sheet sheet, String label) {
        return row(sheet, label).getCell(1).getStringCellValue();
    }
}
