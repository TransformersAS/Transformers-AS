package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.stock.dto.InventoryItemResponse;
import com.transformersas.marketplace.stock.dto.MinimumStockRequest;
import com.transformersas.marketplace.stock.dto.StockAdjustmentRequest;
import com.transformersas.marketplace.stock.dto.StockEntryRequest;
import com.transformersas.marketplace.stock.dto.StockMovementResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Control de inventario del vendedor (CU-15). La tienda nunca llega por la URL ni por el cuerpo: sale de la sesión del
 * vendedor mediante CurrentActorProvider, que además exige el rol activo VENDEDOR.
 */
@RestController
@RequestMapping("/api/seller/inventory")
public class SellerInventoryController {

    private final CurrentActorProvider actor;
    private final SellerInventoryService service;

    public SellerInventoryController(CurrentActorProvider actor, SellerInventoryService service) {
        this.actor = actor;
        this.service = service;
    }

    @GetMapping
    public List<InventoryItemResponse> list(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "false") boolean onlyLow) {
        return service.list(actor.storeId(), q, onlyLow);
    }

    @GetMapping("/{productId}/movements")
    public List<StockMovementResponse> movements(@PathVariable Long productId) {
        return service.movements(actor.storeId(), productId);
    }

    @PostMapping("/{productId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryItemResponse registerEntry(@PathVariable Long productId,
                                               @Valid @RequestBody StockEntryRequest request) {
        Long storeId = actor.storeId();
        return service.registerEntry(storeId, actor.actorId(), productId, request.quantity(), request.reason());
    }

    @PostMapping("/{productId}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryItemResponse registerAdjustment(@PathVariable Long productId,
                                                    @Valid @RequestBody StockAdjustmentRequest request) {
        Long storeId = actor.storeId();
        return service.registerAdjustment(storeId, actor.actorId(), productId, request.newStock(), request.reason());
    }

    @PutMapping("/{productId}/minimum")
    public InventoryItemResponse setMinimum(@PathVariable Long productId,
                                            @Valid @RequestBody MinimumStockRequest request) {
        return service.setMinimum(actor.storeId(), productId, request.minStock());
    }
}
