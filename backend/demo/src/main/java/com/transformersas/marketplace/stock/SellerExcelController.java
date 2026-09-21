package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.stock.dto.ProductImportResult;
import com.transformersas.marketplace.stock.dto.StockImportResult;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Cargas masivas con plantillas de Excel de CU-15: descargar las dos plantillas y subirlas ya llenas. Como el resto de
 * endpoints del vendedor, la tienda sale de la sesión mediante CurrentActorProvider, que exige el rol activo VENDEDOR.
 */
@RestController
@RequestMapping("/api/seller/inventory")
public class SellerExcelController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CurrentActorProvider actor;
    private final SellerInventoryService inventory;
    private final ProductImportService productImport;
    private final StockImportService stockImport;

    public SellerExcelController(CurrentActorProvider actor, SellerInventoryService inventory,
                                 ProductImportService productImport, StockImportService stockImport) {
        this.actor = actor;
        this.inventory = inventory;
        this.productImport = productImport;
        this.stockImport = stockImport;
    }

    @GetMapping("/templates/products")
    public ResponseEntity<byte[]> productsTemplate() throws IOException {
        actor.storeId(); // la plantilla no depende de la tienda, pero solo un vendedor puede descargarla
        return download("plantilla-productos.xlsx", ExcelTemplates.products());
    }

    /** La plantilla de inventario ya trae los productos de la tienda con su stock y lo reservado. */
    @GetMapping("/templates/stock")
    public ResponseEntity<byte[]> stockTemplate() throws IOException {
        return download("plantilla-inventario.xlsx", ExcelTemplates.stock(inventory.list(actor.storeId(), null, false)));
    }

    @PostMapping(value = "/imports/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductImportResult importProducts(@RequestParam("file") MultipartFile file) {
        return productImport.importProducts(actor.storeId(), file);
    }

    @PostMapping(value = "/imports/stock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StockImportResult importStock(@RequestParam("file") MultipartFile file) {
        Long storeId = actor.storeId();
        return stockImport.importStock(storeId, actor.actorId(), file);
    }

    private ResponseEntity<byte[]> download(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType(XLSX))
                .body(content);
    }
}
