package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-15: plantillas de Excel para el alta masiva de productos y la actualización masiva de inventario. */
class SellerExcelTests extends AbstractIntegrationTest {

    private static final String API = "/api/seller/inventory";
    private static final String XLSX = SellerExcelController.XLSX;

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        cleanCatalog();
        // Desde CU-18 el vendedor debe ser el dueño de la tienda cuyo X-Store-Id envía.
        seller = sellerOfStore("seller@example.com", 1);
        seedStore(2, "Otra tienda");
        jdbc.update("INSERT INTO categories (name) VALUES ('Ropa')");
        jdbc.update("INSERT INTO brands (name) VALUES ('Nike')");
        jdbc.update("INSERT INTO brands (name, active) VALUES ('Antigua', FALSE)");
    }

    /** Deja la base sin datos de este caso de uso para no estorbar a las demás clases de prueba. */
    @AfterEach
    void tearDown() {
        cleanCatalog();
    }

    private void cleanCatalog() {
        jdbc.update("DELETE FROM stock_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM product_attribute_values");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM brands");
        jdbc.update("UPDATE categories SET parent_id = NULL");
        jdbc.update("DELETE FROM categories");
    }

    // ---------- Ayudas ----------

    /** Arma un .xlsx: la primera fila son los encabezados; cada valor es texto, número o null (celda vacía). */
    private static byte[] workbook(List<String> headers, List<List<Object>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Datos");
            write(sheet, 0, new ArrayList<>(headers));
            for (int index = 0; index < rows.size(); index++) {
                write(sheet, index + 1, rows.get(index));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void write(Sheet sheet, int rowNumber, List<Object> values) {
        Row row = sheet.createRow(rowNumber);
        for (int column = 0; column < values.size(); column++) {
            Object value = values.get(column);
            if (value instanceof Number number) {
                row.createCell(column).setCellValue(number.doubleValue());
            } else if (value != null) {
                row.createCell(column).setCellValue(value.toString());
            }
        }
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    /** Como performAsSeller, pero para peticiones multipart, que en esta versión de Spring son de otro tipo. */
    private ResultActions multipartAs(Session session, MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken())
                .header("X-Store-Id", 1));
    }

    private ResultActions upload(String path, byte[] content, String fileName) throws Exception {
        return multipartAs(seller, multipart(API + path).file(new MockMultipartFile("file", fileName, XLSX, content)));
    }

    private ResultActions importProducts(List<List<Object>> rows) throws Exception {
        return upload("/imports/products", workbook(ExcelTemplates.PRODUCT_HEADERS, rows), "productos.xlsx");
    }

    private ResultActions importStock(List<List<Object>> rows) throws Exception {
        return upload("/imports/stock", workbook(ExcelTemplates.STOCK_HEADERS, rows), "inventario.xlsx");
    }

    private void reserve(long productId, int quantity) {
        jdbc.update("INSERT INTO inventory_reservations(product_id, quantity, status, created_at, expires_at) "
                        + "VALUES (?,?,?,?,?)", productId, quantity, "ACTIVE", Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)));
    }

    private int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    // ---------- Plantillas ----------

    @Test
    void laPlantillaDeProductosTraeEncabezadosListasEInstrucciones() throws Exception {
        byte[] file = performAsSeller(seller, 1, get(API + "/templates/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", containsString("plantilla-productos.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Productos");
            assertThat(sheet.getRow(0).cellIterator()).toIterable()
                    .extracting(cell -> cell.getStringCellValue()).containsExactlyElementsOf(ExcelTemplates.PRODUCT_HEADERS);
            assertThat(sheet.getDataValidations()).hasSize(1);
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Instrucciones");
        }
    }

    @Test
    void laPlantillaDeInventarioYaTraeLosProductosDeLaTienda() throws Exception {
        long lamp = seedProduct(1, "Lámpara", 10, "50000");
        reserve(lamp, 3);
        seedProduct(2, "De otra tienda", 7, "1000");
        long retired = seedProduct(1, "Retirado", 5, "1000");
        jdbc.update("UPDATE products SET status = 'RETIRED', active = FALSE WHERE id = ?", retired);

        byte[] file = performAsSeller(seller, 1, get(API + "/templates/stock"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("plantilla-inventario.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Inventario");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            Row data = sheet.getRow(1);
            assertThat(data.getCell(0).getNumericCellValue()).isEqualTo(lamp);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Lámpara");
            assertThat(data.getCell(2).getNumericCellValue()).isEqualTo(10);
            assertThat(data.getCell(3).getNumericCellValue()).isEqualTo(3);
            assertThat(sheet.getDataValidations()).hasSize(1);
        }
    }

    @Test
    void soloUnVendedorUsaLasPlantillasYLasCargas() throws Exception {
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        byte[] file = workbook(ExcelTemplates.PRODUCT_HEADERS, List.of(row("Camiseta", null, 1, 1, "Ropa")));

        performAsSeller(buyer, 1, get(API + "/templates/products")).andExpect(status().isForbidden());
        performAsSeller(buyer, 1, get(API + "/templates/stock")).andExpect(status().isForbidden());
        multipartAs(buyer, multipart(API + "/imports/products").file(new MockMultipartFile("file", "a.xlsx", XLSX, file)))
                .andExpect(status().isForbidden());
        mvc.perform(get(API + "/templates/products")).andExpect(status().isUnauthorized());
        assertThat(count("products")).isZero();
    }

    // ---------- Alta masiva de productos ----------

    @Test
    void laCargaCreaLosProductosYPublicaLosQueDicenSi() throws Exception {
        importProducts(List.of(
                row("Camiseta roja", "Algodón", 25000, 10, "Ropa", "nike", "http://img/a.jpg;http://img/b.jpg", "SI"),
                row("Camiseta azul", null, "18000.50", "5", "ropa", null, null, null),
                row(),
                row("Gorra", "Con visera", 9000, 0, "Ropa", null, "http://img/c.jpg", "no")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.published").value(1));

        assertThat(jdbc.queryForList("SELECT name, status, stock FROM products WHERE store_id = 1 ORDER BY id"))
                .extracting(product -> product.get("name") + "/" + product.get("status") + "/" + product.get("stock"))
                .containsExactly("Camiseta roja/ACTIVE/10", "Camiseta azul/DRAFT/5", "Gorra/DRAFT/0");
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE name = 'Camiseta azul'", java.math.BigDecimal.class))
                .isEqualByComparingTo("18000.50");
        assertThat(count("product_images")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id "
                + "WHERE p.name = 'Camiseta roja' AND b.name = 'Nike'", Integer.class)).isEqualTo(1);
    }

    @Test
    void siUnaFilaFallaSeDevuelvenTodosLosErroresYNoSeGuardaNada() throws Exception {
        String longText = "x".repeat(256);
        String longImage = "http://img/" + "x".repeat(500);
        importProducts(List.of(
                row(null, null, 1000, 1, "Ropa"),                                   // 2: falta el nombre
                row("Precio malo", null, "abc", 1, "Ropa"),                          // 3: precio no numérico
                row("Precio con decimales", null, "10.123", 1, "Ropa"),              // 4: más de dos decimales
                row("Categoría rara", null, 1000, 1, "Zapatos"),                     // 5: categoría inexistente
                row("Marca rara", null, 1000, 1, "Ropa", "Adidas"),                  // 6: marca inexistente
                row("Marca inactiva", null, 1000, 1, "Ropa", "Antigua"),             // 7: marca inactiva
                row("Publicar raro", null, 1000, 1, "Ropa", null, "http://img/a.jpg", "TAL VEZ"), // 8
                row("Sin imagen", null, 1000, 1, "Ropa", null, null, "SI"),          // 9: publicar exige imagen
                row("Inventario decimal", null, 1000, 1.5, "Ropa"),                  // 10
                row("Inventario negativo", null, 1000, -1, "Ropa"),                  // 11
                row("Descripción larga", longText, 1000, 1, "Ropa"),                 // 12
                row("Imagen larga", null, 1000, 1, "Ropa", null, longImage),         // 13
                row("Fila correcta", null, 1000, 1, "Ropa")))                        // 14: bien, pero no se guarda
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCEL_ROW_ERRORS"))
                .andExpect(jsonPath("$.details", hasSize(12)))
                .andExpect(jsonPath("$.details[0].row").value(2))
                .andExpect(jsonPath("$.details[0].message", containsString("Nombre")))
                .andExpect(jsonPath("$.details[1].message", containsString("Precio")))
                .andExpect(jsonPath("$.details[2].message", containsString("2 decimales")))
                .andExpect(jsonPath("$.details[3].message", containsString("categoría")))
                .andExpect(jsonPath("$.details[4].message", containsString("Adidas")))
                .andExpect(jsonPath("$.details[5].message", containsString("Antigua")))
                .andExpect(jsonPath("$.details[6].message", containsString("SI o NO")))
                .andExpect(jsonPath("$.details[7].message", containsString("al menos una imagen")))
                .andExpect(jsonPath("$.details[8].message", containsString("Inventario")))
                .andExpect(jsonPath("$.details[10].message", containsString("255")))
                .andExpect(jsonPath("$.details[11].message", containsString("500")))
                .andExpect(jsonPath("$.details[11].row").value(13));

        assertThat(count("products")).isZero();
        assertThat(count("product_images")).isZero();
    }

    @Test
    void unArchivoQueNoEsLaPlantillaOQueNoEsExcelSeRechaza() throws Exception {
        upload("/imports/products", workbook(List.of("A", "B"), List.of(row("1", "2"))), "otra.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_WRONG_TEMPLATE"));
        upload("/imports/products", emptySheet(), "vacia.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_WRONG_TEMPLATE"));
        upload("/imports/products", workbook(ExcelTemplates.PRODUCT_HEADERS, List.of()), "solo-encabezado.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_EMPTY"));
        upload("/imports/products", "a,b".getBytes(), "datos.csv")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_INVALID_FILE"));
        upload("/imports/products", "no soy un excel".getBytes(), "falso.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_INVALID_FILE"));
        upload("/imports/products", new byte[0], "vacio.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_INVALID_FILE"));
        upload("/imports/products", noSheets(), "sin-hojas.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_INVALID_FILE"));
        multipartAs(seller, multipart(API + "/imports/products")).andExpect(status().isBadRequest());

        assertThat(count("products")).isZero();
    }

    @Test
    void unaCargaConDemasiadasFilasSeRechaza() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index <= ExcelSheet.MAX_ROWS; index++) {
            rows.add(row("Producto " + index, null, 1000, 1, "Ropa"));
        }

        importProducts(rows).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCEL_TOO_MANY_ROWS"));

        assertThat(count("products")).isZero();
    }

    // ---------- Actualización masiva de inventario ----------

    @Test
    void laCargaDeInventarioAplicaEntradasYAjustesYLosRegistraEnElHistorial() throws Exception {
        long lamp = seedProduct(1, "Lámpara", 10, "50000");
        long table = seedProduct(1, "Mesa", 4, "80000");
        long chair = seedProduct(1, "Silla", 6, "30000");
        jdbc.update("UPDATE products SET min_stock = 20 WHERE id = ?", lamp);

        importStock(List.of(
                row(lamp, "Lámpara", 10, 0, "ENTRADA", 5, "Reposición"),
                row(String.valueOf(table), "Mesa", 4, 0, "ajuste", "2", "Conteo físico"),
                row(chair, "Silla", 6, 0, null, null, "sin movimiento, se ignora"),
                row()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));

        assertThat(stockOf(lamp)).isEqualTo(15);
        assertThat(stockOf(table)).isEqualTo(2);
        assertThat(stockOf(chair)).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT type, quantity, stock_after, reason FROM stock_movements ORDER BY id"))
                .extracting(movement -> movement.get("type") + "/" + movement.get("quantity") + "/"
                        + movement.get("stock_after") + "/" + movement.get("reason"))
                .containsExactly("ENTRY/5/15/Reposición", "ADJUSTMENT/-2/2/Conteo físico");
        // La entrada dejó a la lámpara (15) por debajo de su mínimo (20): la alerta también funciona con el Excel.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'LOW_STOCK'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void siUnaFilaDeInventarioFallaNoSeAplicaNadaYSeInformanTodosLosErrores() throws Exception {
        long p1 = seedProduct(1, "P1", 10, "1000");
        long p2 = seedProduct(1, "P2", 10, "1000");
        long p3 = seedProduct(1, "P3", 10, "1000");
        long p4 = seedProduct(1, "P4", 10, "1000");
        long p5 = seedProduct(1, "P5", 10, "1000");
        long p6 = seedProduct(1, "P6", 10, "1000");
        long p7 = seedProduct(1, "P7", 10, "1000");
        long p8 = seedProduct(1, "P8", 10, "1000");
        long foreign = seedProduct(2, "Ajeno", 10, "1000");
        reserve(p5, 6);

        importStock(List.of(
                row(p1, "P1", 10, 0, "ENTRADA", 1, null),               // 2: bien (se revierte al haber errores)
                row(p1, "P1", 10, 0, "ENTRADA", 2, null),               // 3: producto repetido
                row(999999, "No existe", 0, 0, "ENTRADA", 1, null),     // 4: producto inexistente
                row(foreign, "Ajeno", 10, 0, "ENTRADA", 1, null),       // 5: producto de otra tienda
                row(p2, "P2", 10, 0, "DEVOLUCION", 1, null),            // 6: tipo inválido
                row(p3, "P3", 10, 0, "ENTRADA", 0, null),               // 7: entrada sin unidades
                row(p4, "P4", 10, 0, "AJUSTE", 5, null),                // 8: ajuste sin motivo
                row(p5, "P5", 10, 6, "AJUSTE", 2, "Conteo"),            // 9: por debajo de lo reservado
                row(p6, "P6", 10, 0, "AJUSTE", 10, "Conteo"),           // 10: igual al stock actual
                row("abc", "?", 0, 0, "ENTRADA", 1, null),              // 11: ID inválido
                row(p7, "P7", 10, 0, "ENTRADA", null, null),            // 12: falta la cantidad
                row(p8, "P8", 10, 0, "AJUSTE", 3, "Bien")))             // 13: bien (se revierte)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCEL_ROW_ERRORS"))
                .andExpect(jsonPath("$.details", hasSize(10)))
                .andExpect(jsonPath("$.details[0].row").value(3))
                .andExpect(jsonPath("$.details[0].message", containsString("fila 2")))
                .andExpect(jsonPath("$.details[1].message", containsString("Producto 999999")))
                .andExpect(jsonPath("$.details[2].message", containsString("Producto " + foreign)))
                .andExpect(jsonPath("$.details[3].message", containsString("ENTRADA o AJUSTE")))
                .andExpect(jsonPath("$.details[4].message", containsString("al menos 1")))
                .andExpect(jsonPath("$.details[5].message", containsString("Motivo")))
                .andExpect(jsonPath("$.details[6].message", containsString("6 unidades reservadas")))
                .andExpect(jsonPath("$.details[7].message", containsString("igual al stock")))
                .andExpect(jsonPath("$.details[8].message", containsString("ID producto")))
                .andExpect(jsonPath("$.details[9].row").value(12))
                .andExpect(jsonPath("$.details[9].message", containsString("Cantidad")));

        assertThat(stockOf(p1)).isEqualTo(10);
        assertThat(stockOf(p8)).isEqualTo(10);
        assertThat(count("stock_movements")).isZero();
    }

    @Test
    void unaCargaDeInventarioSinMovimientosSeRechaza() throws Exception {
        seedProduct(1, "Lámpara", 10, "50000");
        byte[] template = performAsSeller(seller, 1, get(API + "/templates/stock"))
                .andReturn().getResponse().getContentAsByteArray();

        // La plantilla tal cual se descargó: trae productos pero ningún Tipo ni Cantidad.
        upload("/imports/stock", template, "inventario.xlsx")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCEL_EMPTY"));

        assertThat(count("stock_movements")).isZero();
    }

    // ---------- Archivos de prueba ----------

    private static byte[] emptySheet() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Datos");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] noSheets() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
