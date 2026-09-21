package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.catalog.Brand;
import com.transformersas.marketplace.catalog.BrandRepository;
import com.transformersas.marketplace.product.SellerProductService;
import com.transformersas.marketplace.product.dto.SellerProductRequest;
import com.transformersas.marketplace.stock.ExcelSheet.DataRow;
import com.transformersas.marketplace.stock.ExcelSheet.RowRejected;
import com.transformersas.marketplace.stock.dto.ProductImportResult;
import com.transformersas.marketplace.stock.dto.RowError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Alta masiva de productos desde la plantilla de Excel (CU-15). Cada fila se crea y, si dice SI en "Publicar", se
 * publica con el mismo servicio y las mismas reglas de CU-14. Es todo o nada: si alguna fila falla se revisan todas,
 * se devuelven los errores con su número de fila y la transacción se revierte, así no queda nada a medias.
 */
@Service
@Transactional
public class ProductImportService {

    private final SellerProductService products;
    private final BrandRepository brands;

    public ProductImportService(SellerProductService products, BrandRepository brands) {
        this.products = products;
        this.brands = brands;
    }

    public ProductImportResult importProducts(Long storeId, MultipartFile file) {
        List<DataRow> rows = ExcelSheet.read(file, ExcelTemplates.PRODUCT_HEADERS);
        List<RowError> errors = new ArrayList<>();
        int created = 0;
        int published = 0;
        for (DataRow row : rows) {
            try {
                boolean publish = wantsToPublish(row.cell(7));
                SellerProductRequest request = toRequest(row, publish);
                Long id = products.create(storeId, request).id();
                created++;
                if (publish) {
                    products.publish(storeId, id);
                    published++;
                }
            } catch (RowRejected | ResponseStatusException rejected) {
                errors.add(new RowError(row.number(), reasonOf(rejected)));
            }
        }
        if (!errors.isEmpty()) {
            throw ExcelSheet.withErrors(errors);
        }
        return new ProductImportResult(created, published);
    }

    private SellerProductRequest toRequest(DataRow row, boolean publish) {
        String name = ExcelSheet.required(row.cell(0), "Nombre", 255);
        String description = ExcelSheet.optional(row.cell(1), "Descripción", 255);
        var price = ExcelSheet.price(row.cell(2));
        int stock = ExcelSheet.integer(row.cell(3), "Inventario", 0, 1_000_000);
        String category = ExcelSheet.required(row.cell(4), "Categoría", 255);
        Long brandId = brandId(row.cell(5));
        List<String> images = Arrays.stream(row.cell(6).split(";")).map(String::strip)
                .filter(image -> !image.isEmpty()).toList();
        if (images.stream().anyMatch(image -> image.length() > 500)) {
            throw new RowRejected("Cada dirección de «Imágenes» admite hasta 500 caracteres");
        }
        if (publish && images.isEmpty()) {
            throw new RowRejected("Para publicar necesitas al menos una imagen");
        }
        return new SellerProductRequest(name, description, price, stock, category, brandId, images, List.of(),
                List.of());
    }

    /** La marca se escribe por su nombre; se busca ignorando mayúsculas y debe estar activa. */
    private Long brandId(String name) {
        if (name.isEmpty()) {
            return null;
        }
        return brands.findByNameIgnoreCase(name).filter(Brand::isActive).map(Brand::getId)
                .orElseThrow(() -> new RowRejected("La marca «" + name + "» no existe o está inactiva"));
    }

    private boolean wantsToPublish(String value) {
        return switch (value.toUpperCase()) {
            case "", "NO" -> false;
            case "SI", "SÍ" -> true;
            default -> throw new RowRejected("«Publicar» debe ser SI o NO");
        };
    }

    private String reasonOf(RuntimeException rejected) {
        return rejected instanceof ResponseStatusException status ? status.getReason() : rejected.getMessage();
    }
}
