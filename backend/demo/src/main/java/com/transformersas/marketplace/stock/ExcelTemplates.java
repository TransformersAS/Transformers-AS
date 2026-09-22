package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.stock.dto.InventoryItemResponse;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Genera las dos plantillas de Excel de CU-15: la de alta masiva de productos (vacía) y la de actualización de
 * inventario (ya trae los productos de la tienda). Los encabezados son los mismos que ExcelSheet exige al leer.
 * Cada archivo lleva una segunda hoja con las instrucciones.
 */
final class ExcelTemplates {

    static final List<String> PRODUCT_HEADERS =
            List.of("Nombre", "Descripción", "Precio", "Inventario", "Categoría", "Marca", "Imágenes", "Publicar");

    static final List<String> STOCK_HEADERS =
            List.of("ID producto", "Producto", "Stock actual", "Reservado", "Tipo", "Cantidad", "Motivo");

    /** Posición de las columnas que el vendedor completa. */
    static final int PRODUCT_PUBLISH_COLUMN = 7;
    static final int STOCK_TYPE_COLUMN = 4;

    private ExcelTemplates() {
    }

    static byte[] products() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Productos");
            header(workbook, sheet, PRODUCT_HEADERS);
            dropdown(sheet, PRODUCT_PUBLISH_COLUMN, "SI", "NO");
            instructions(workbook, List.of(
                    "Una fila por producto. No cambies ni borres la fila 1.",
                    "Obligatorios: Nombre, Precio, Inventario y Categoría. La categoría debe existir y estar activa.",
                    "Marca: opcional; debe existir y estar activa.",
                    "Imágenes: una o varias direcciones separadas por punto y coma (la primera es la principal).",
                    "Publicar: SI publica el producto al cargar (exige al menos una imagen); NO o vacío lo deja como borrador.",
                    "Máximo " + ExcelSheet.MAX_ROWS + " filas por carga.",
                    "Si alguna fila tiene errores no se guarda nada y se te indica la fila de cada error."));
            return bytes(workbook);
        }
    }

    static byte[] stock(List<InventoryItemResponse> items) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Inventario");
            header(workbook, sheet, STOCK_HEADERS);
            int rowNumber = 1;
            for (InventoryItemResponse item : items) {
                Row row = sheet.createRow(rowNumber++);
                row.createCell(0).setCellValue(item.productId());
                row.createCell(1).setCellValue(item.name());
                row.createCell(2).setCellValue(item.stock());
                row.createCell(3).setCellValue(item.reserved());
            }
            dropdown(sheet, STOCK_TYPE_COLUMN, "ENTRADA", "AJUSTE");
            instructions(workbook, List.of(
                    "Cada fila es un producto de tu tienda. No cambies las columnas A a D: son informativas.",
                    "Para mover el inventario completa Tipo y Cantidad. Las filas sin Tipo ni Cantidad se ignoran.",
                    "ENTRADA: Cantidad son las unidades que llegaron y se suman al stock.",
                    "AJUSTE: Cantidad es el conteo real que tienes; exige Motivo y no puede ser menor que lo Reservado.",
                    "Máximo " + ExcelSheet.MAX_ROWS + " filas por carga.",
                    "Si alguna fila tiene errores no se aplica nada y se te indica la fila de cada error."));
            return bytes(workbook);
        }
    }

    // ---------- Formato ----------

    private static void header(Workbook workbook, Sheet sheet, List<String> headers) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        Row row = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            row.createCell(column).setCellValue(headers.get(column));
            row.getCell(column).setCellStyle(style);
            sheet.setColumnWidth(column, 20 * 256);
        }
    }

    /** Lista desplegable con las opciones válidas para una columna, en todas las filas de datos posibles. */
    private static void dropdown(Sheet sheet, int column, String... options) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(options);
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(1, ExcelSheet.MAX_ROWS, column, column));
        sheet.addValidationData(validation);
    }

    private static void instructions(Workbook workbook, List<String> lines) {
        Sheet sheet = workbook.createSheet("Instrucciones");
        sheet.setColumnWidth(0, 110 * 256);
        for (int index = 0; index < lines.size(); index++) {
            sheet.createRow(index).createCell(0).setCellValue(lines.get(index));
        }
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
