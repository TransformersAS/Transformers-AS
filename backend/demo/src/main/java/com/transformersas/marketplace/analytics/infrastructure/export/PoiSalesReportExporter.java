package com.transformersas.marketplace.analytics.infrastructure.export;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.IndicatorComparison;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.CategorySales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.ProductSales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;
import com.transformersas.marketplace.analytics.domain.model.SalesIndicators;
import com.transformersas.marketplace.analytics.domain.model.SalesReport;
import com.transformersas.marketplace.analytics.domain.model.SalesReport.PeriodReport;
import com.transformersas.marketplace.analytics.domain.repository.SalesReportExporter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Reporte Excel con lo que el vendedor ve en pantalla (RF-127): tienda, periodos, filtros, indicadores con su
 * comparación, productos más vendidos, categorías y serie temporal. Los montos son números con formato de moneda, para
 * que el vendedor pueda volver a calcular sobre ellos.
 */
@Component
public class PoiSalesReportExporter implements SalesReportExporter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Indicadores en el orden en que se muestran, con su nombre en pantalla. */
    private static final Map<String, String> LABELS = Map.of(
            "sales", "Ventas", "refunds", "Reembolsos", "netRevenue", "Ingresos netos", "costs", "Costos",
            "profit", "Ganancia", "orders", "Pedidos", "units", "Unidades vendidas", "averageTicket", "Ticket promedio");
    private static final List<String> MONEY = List.of("sales", "refunds", "netRevenue", "costs", "profit",
            "averageTicket");

    @Override
    public byte[] export(SalesReport report) {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(book);
            summary(book.createSheet("Resumen"), report, styles);
            topProducts(book.createSheet("Más vendidos"), report, styles);
            categories(book.createSheet("Categorías"), report, styles);
            series(book.createSheet("Serie"), report, styles);
            book.write(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw new ExportFailedException("No se pudo generar el archivo Excel", failure);
        }
    }

    private void summary(Sheet sheet, SalesReport report, Styles styles) {
        Cursor at = new Cursor(sheet);
        at.row().text(0, "Analítica de ventas y rentabilidad", styles.title);
        at.skip();
        at.row().label(0, "Tienda", styles).text(1, report.storeName(), null);
        at.row().label(0, "Generado", styles).text(1, LocalDateTime.now().format(TIMESTAMP), null);
        at.row().label(0, "Periodo", styles).text(1, describe(report.current().period()), null);
        at.row().label(0, "Comparación", styles)
                .text(1, report.hasComparison() ? describe(report.comparison().period()) : "Sin comparación", null);
        at.row().label(0, "Producto", styles)
                .text(1, report.productName() == null ? "Todos" : report.productName(), null);
        at.row().label(0, "Categoría", styles)
                .text(1, report.filter().category() == null ? "Todas" : report.filter().category(), null);
        at.skip();

        SalesIndicators current = report.current().indicators();
        if (report.hasComparison()) {
            at.row().header(styles, "Indicador", "Periodo", "Comparación", "Diferencia", "Variación %");
            for (IndicatorComparison row : report.comparisons()) {
                boolean money = MONEY.contains(row.indicator());
                Line line = at.row().text(0, LABELS.get(row.indicator()), null)
                        .number(1, row.current(), money, styles).number(2, row.previous(), money, styles)
                        .number(3, row.difference(), money, styles);
                if (row.percentAvailable()) {
                    line.number(4, row.percentChange(), false, styles);
                } else {
                    line.text(4, "No calculable (base en cero)", null);
                }
            }
        } else {
            at.row().header(styles, "Indicador", "Periodo");
            // Sin comparación solo interesa el valor actual; between(...) da los indicadores en el orden de pantalla.
            for (IndicatorComparison row : IndicatorComparison.between(current, current)) {
                at.row().text(0, LABELS.get(row.indicator()), null)
                        .number(1, row.current(), MONEY.contains(row.indicator()), styles);
            }
        }
        at.row().text(0, "Margen %", null)
                .text(1, current.marginPercent() == null ? "No calculable" : current.marginPercent() + " %", null);
        at.skip();
        if (!current.hasData()) {
            at.row().text(0, "No hubo ventas en el periodo con estos filtros.", null);
        }
        if (!current.costComplete()) {
            at.row().text(0, current.unitsWithoutCost() + " unidades no tienen costo unitario registrado: los costos y "
                    + "la ganancia solo incluyen las que sí lo tienen.", null);
        }
        at.row().text(0, "Ventas: valor de los productos menos descuentos, sin envío. Ingresos netos: ventas menos "
                + "reembolsos. No incluye pedidos cancelados.", null);
        sheet.setColumnWidth(0, 32 * 256);
        for (int column = 1; column <= 4; column++) {
            sheet.setColumnWidth(column, 20 * 256);
        }
    }

    private void topProducts(Sheet sheet, SalesReport report, Styles styles) {
        Cursor at = new Cursor(sheet);
        productTable(at, "Periodo " + describe(report.current().period()), report.current(), styles);
        if (report.hasComparison()) {
            at.skip();
            productTable(at, "Comparación " + describe(report.comparison().period()), report.comparison(), styles);
        }
        widths(sheet, 6, 34, 18, 12, 16, 16, 16, 16, 18);
    }

    private void productTable(Cursor at, String title, PeriodReport period, Styles styles) {
        at.row().text(0, title, styles.bold);
        at.row().header(styles, "#", "Producto", "Categoría", "Unidades", "Ventas", "Reembolsos", "Costos",
                "Ganancia", "Unidades sin costo");
        int rank = 1;
        for (ProductSales product : period.topProducts()) {
            at.row().number(0, BigDecimal.valueOf(rank++), false, styles).text(1, product.name(), null)
                    .text(2, product.category(), null).number(3, BigDecimal.valueOf(product.units()), false, styles)
                    .number(4, product.sales(), true, styles).number(5, product.refunds(), true, styles)
                    .number(6, product.costs(), true, styles).number(7, product.profit(), true, styles)
                    .number(8, BigDecimal.valueOf(product.unitsWithoutCost()), false, styles);
        }
        if (period.topProducts().isEmpty()) {
            at.row().text(0, "Sin ventas en este periodo.", null);
        }
    }

    private void categories(Sheet sheet, SalesReport report, Styles styles) {
        Cursor at = new Cursor(sheet);
        at.row().header(styles, "Categoría", "Unidades", "Ventas");
        for (CategorySales category : report.current().categories()) {
            at.row().text(0, category.category(), null).number(1, BigDecimal.valueOf(category.units()), false, styles)
                    .number(2, category.sales(), true, styles);
        }
        widths(sheet, 28, 14, 18);
    }

    private void series(Sheet sheet, SalesReport report, Styles styles) {
        Cursor at = new Cursor(sheet);
        boolean compare = report.hasComparison();
        at.row().text(0, "Agrupación: " + switch (report.granularity()) {
            case DAY -> "por día";
            case WEEK -> "por semana (desde el lunes)";
            case MONTH -> "por mes";
        }, null);
        if (compare) {
            at.row().header(styles, "Desde", "Ventas", "Costos", "Pedidos", "Desde (comparación)",
                    "Ventas (comparación)", "Costos (comparación)", "Pedidos (comparación)");
        } else {
            at.row().header(styles, "Desde", "Ventas", "Costos", "Pedidos");
        }
        List<SeriesPoint> main = report.current().series();
        List<SeriesPoint> other = compare ? report.comparison().series() : List.of();
        for (int index = 0; index < Math.max(main.size(), other.size()); index++) {
            Line line = at.row();
            if (index < main.size()) {
                point(line, 0, main.get(index), styles);
            }
            if (index < other.size()) {
                point(line, 4, other.get(index), styles);
            }
        }
        widths(sheet, 14, 16, 16, 10, 20, 20, 20, 20);
    }

    private static void point(Line line, int column, SeriesPoint point, Styles styles) {
        line.text(column, point.start().toString(), null).number(column + 1, point.sales(), true, styles)
                .number(column + 2, point.costs(), true, styles)
                .number(column + 3, BigDecimal.valueOf(point.orders()), false, styles);
    }

    private static String describe(AnalyticsPeriod period) {
        return period.from() + " a " + period.to();
    }

    private static void widths(Sheet sheet, int... characters) {
        for (int column = 0; column < characters.length; column++) {
            sheet.setColumnWidth(column, characters[column] * 256);
        }
    }

    /** Estilos compartidos: el libro admite un número limitado, así que se crean una vez. */
    private static final class Styles {
        final CellStyle title;
        final CellStyle bold;
        final CellStyle header;
        final CellStyle money;
        final CellStyle integer;

        Styles(Workbook book) {
            Font titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = book.createCellStyle();
            title.setFont(titleFont);
            Font boldFont = book.createFont();
            boldFont.setBold(true);
            bold = book.createCellStyle();
            bold.setFont(boldFont);
            header = book.createCellStyle();
            header.setFont(boldFont);
            money = book.createCellStyle();
            money.setDataFormat(book.createDataFormat().getFormat("\"$\" #,##0.00"));
            integer = book.createCellStyle();
            integer.setDataFormat(book.createDataFormat().getFormat("#,##0.##"));
        }
    }

    /** Avanza fila por fila. */
    private static final class Cursor {
        private final Sheet sheet;
        private int next;

        Cursor(Sheet sheet) {
            this.sheet = sheet;
        }

        Line row() {
            return new Line(sheet.createRow(next++));
        }

        void skip() {
            next++;
        }
    }

    private record Line(Row row) {
        Line text(int column, String value, CellStyle style) {
            Cell cell = row.createCell(column);
            cell.setCellValue(value);
            if (style != null) {
                cell.setCellStyle(style);
            }
            return this;
        }

        Line label(int column, String value, Styles styles) {
            return text(column, value, styles.bold);
        }

        Line number(int column, BigDecimal value, boolean money, Styles styles) {
            Cell cell = row.createCell(column);
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(money ? styles.money : styles.integer);
            return this;
        }

        Line header(Styles styles, String... titles) {
            for (int column = 0; column < titles.length; column++) {
                text(column, titles[column], styles.header);
            }
            return this;
        }
    }
}
