package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stock.ExcelSheet.DataRow;
import com.transformersas.marketplace.stock.ExcelSheet.RowRejected;
import com.transformersas.marketplace.stock.dto.RowError;
import com.transformersas.marketplace.stock.dto.StockImportResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Actualización masiva de inventario desde la plantilla de Excel (CU-15). Cada fila con Tipo y Cantidad es una entrada
 * o un ajuste y pasa por SellerInventoryService, así que valen las mismas reglas y queda en el historial. Es todo o
 * nada: si alguna fila falla se revisan todas, se devuelven los errores con su número de fila y la transacción se
 * revierte para que el inventario no quede a medias.
 */
@Service
@Transactional
public class StockImportService {

    private final SellerInventoryService inventory;

    public StockImportService(SellerInventoryService inventory) {
        this.inventory = inventory;
    }

    public StockImportResult importStock(Long storeId, Long actorId, MultipartFile file) {
        List<DataRow> rows = ExcelSheet.read(file, ExcelTemplates.STOCK_HEADERS);
        List<RowError> errors = new ArrayList<>();
        Map<Long, Integer> seen = new HashMap<>();
        int applied = 0;
        for (DataRow row : rows) {
            if (row.cell(4).isEmpty() && row.cell(5).isEmpty()) {
                continue; // fila sin movimiento: el vendedor no quiso tocar ese producto
            }
            Long productId = null;
            try {
                productId = (long) ExcelSheet.integer(row.cell(0), "ID producto", 1, Integer.MAX_VALUE);
                Integer firstRow = seen.putIfAbsent(productId, row.number());
                if (firstRow != null) {
                    throw new RowRejected("El producto ya aparece en la fila " + firstRow);
                }
                move(storeId, actorId, productId, row);
                applied++;
            } catch (RowRejected | ResponseStatusException rejected) {
                errors.add(new RowError(row.number(), reasonOf(productId, rejected)));
            }
        }
        if (!errors.isEmpty()) {
            throw ExcelSheet.withErrors(errors);
        }
        if (applied == 0) {
            throw BusinessException.invalid("EXCEL_EMPTY", "Ninguna fila tiene Tipo y Cantidad para procesar");
        }
        return new StockImportResult(applied);
    }

    private void move(Long storeId, Long actorId, Long productId, DataRow row) {
        int quantity = ExcelSheet.integer(row.cell(5), "Cantidad", 0, 1_000_000);
        switch (row.cell(4).toUpperCase()) {
            case "ENTRADA" -> {
                if (quantity < 1) {
                    throw new RowRejected("En una entrada «Cantidad» debe ser al menos 1");
                }
                inventory.registerEntry(storeId, actorId, productId, quantity,
                        ExcelSheet.optional(row.cell(6), "Motivo", 255));
            }
            case "AJUSTE" -> inventory.registerAdjustment(storeId, actorId, productId, quantity,
                    ExcelSheet.required(row.cell(6), "Motivo", 255));
            default -> throw new RowRejected("«Tipo» debe ser ENTRADA o AJUSTE");
        }
    }

    private String reasonOf(Long productId, RuntimeException rejected) {
        String reason = rejected instanceof ResponseStatusException status ? status.getReason() : rejected.getMessage();
        return productId != null && rejected instanceof ResponseStatusException
                ? "Producto " + productId + ": " + reason : reason;
    }
}
