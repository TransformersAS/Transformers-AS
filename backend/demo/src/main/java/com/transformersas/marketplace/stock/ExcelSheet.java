package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stock.dto.RowError;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee la primera hoja de un archivo .xlsx (CU-15). Comprueba que sea Excel, que traiga los encabezados de la
 * plantilla y que no pase del máximo de filas, y devuelve cada fila con texto en todas sus celdas. Las filas
 * completamente vacías se ignoran. Las reglas de cada carga (qué significa cada columna) las decide quien lo usa.
 */
final class ExcelSheet {

    static final int MAX_ROWS = 500;

    /** Una fila de datos: "number" es el número de fila en la hoja (la 1 es el encabezado) y "cells" sus textos. */
    record DataRow(int number, List<String> cells) {

        String cell(int index) {
            return index < cells.size() ? cells.get(index) : "";
        }
    }

    private ExcelSheet() {
    }

    static List<DataRow> read(MultipartFile file, List<String> headers) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalid("EXCEL_INVALID_FILE", "Adjunta el archivo Excel de la plantilla");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx")) {
            throw BusinessException.invalid("EXCEL_INVALID_FILE", "El archivo debe ser un Excel con extensión .xlsx");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = open(input)) {
            return rows(workbook.getSheetAt(0), headers);
        } catch (IOException failure) {
            throw notExcel();
        }
    }

    /** Abre el libro; un archivo que no es un .xlsx real (o que no trae ninguna hoja) se rechaza con un mensaje claro. */
    private static Workbook open(InputStream input) {
        try {
            Workbook workbook = new XSSFWorkbook(input);
            if (workbook.getNumberOfSheets() == 0) {
                throw notExcel();
            }
            return workbook;
        } catch (IOException | RuntimeException failure) {
            throw failure instanceof BusinessException business ? business : notExcel();
        }
    }

    private static BusinessException notExcel() {
        return BusinessException.invalid("EXCEL_INVALID_FILE", "El archivo no es un Excel (.xlsx) válido");
    }

    private static List<DataRow> rows(Sheet sheet, List<String> headers) {
        DataFormatter formatter = new DataFormatter();
        Row headerRow = sheet.getRow(0);
        for (int column = 0; column < headers.size(); column++) {
            String found = headerRow == null ? "" : text(headerRow.getCell(column), formatter);
            if (!found.equalsIgnoreCase(headers.get(column))) {
                throw BusinessException.invalid("EXCEL_WRONG_TEMPLATE",
                        "Usa la plantilla descargada: la primera fila debe traer las columnas " + headers);
            }
        }
        List<DataRow> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < headers.size(); column++) {
                cells.add(row == null ? "" : text(row.getCell(column), formatter));
            }
            if (cells.stream().allMatch(String::isEmpty)) {
                continue;
            }
            result.add(new DataRow(index + 1, cells));
        }
        if (result.isEmpty()) {
            throw BusinessException.invalid("EXCEL_EMPTY", "El archivo no tiene filas para procesar");
        }
        if (result.size() > MAX_ROWS) {
            throw BusinessException.invalid("EXCEL_TOO_MANY_ROWS",
                    "El archivo tiene " + result.size() + " filas; el máximo por carga es " + MAX_ROWS);
        }
        return result;
    }

    // ---------- Lectura de celdas: cada regla que falla rechaza solo su fila ----------

    /** Una fila que no cumple una regla; quien procesa el archivo la convierte en un RowError y sigue con las demás. */
    static final class RowRejected extends RuntimeException {

        RowRejected(String message) {
            super(message, null, false, false);
        }
    }

    static String required(String value, String field, int maxLength) {
        if (value.isEmpty()) {
            throw new RowRejected("«" + field + "» es obligatorio");
        }
        return optional(value, field, maxLength);
    }

    /** Texto opcional: vacío se vuelve null. */
    static String optional(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new RowRejected("«" + field + "» admite hasta " + maxLength + " caracteres");
        }
        return value.isEmpty() ? null : value;
    }

    static BigDecimal price(String value) {
        BigDecimal price = decimal(value, "Precio");
        if (price.signum() < 0 || price.stripTrailingZeros().scale() > 2) {
            throw new RowRejected("«Precio» debe ser un número desde 0 con máximo 2 decimales");
        }
        return price;
    }

    static int integer(String value, String field, int min, int max) {
        try {
            int number = decimal(value, field).intValueExact();
            if (number >= min && number <= max) {
                return number;
            }
        } catch (ArithmeticException notAnInteger) {
            // se informa abajo con el mismo mensaje
        }
        throw new RowRejected("«" + field + "» debe ser un número entero entre " + min + " y " + max);
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException notANumber) {
            throw new RowRejected("«" + field + "» debe ser un número");
        }
    }

    /** El error que se devuelve cuando alguna fila falla: 400 con la lista de filas en "details". */
    static BusinessException withErrors(List<RowError> errors) {
        return new BusinessException(BusinessException.Kind.INVALID, "EXCEL_ROW_ERRORS",
                "El archivo tiene " + errors.size() + " fila(s) con errores; no se guardó nada", errors);
    }

    /** Texto de una celda. Los números se leen tal cual (sin formato de miles) para poder convertirlos después. */
    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return formatter.formatCellValue(cell).strip();
    }
}
