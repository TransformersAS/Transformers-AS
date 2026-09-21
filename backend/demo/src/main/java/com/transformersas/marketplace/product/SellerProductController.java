package com.transformersas.marketplace.product;

import com.transformersas.marketplace.product.dto.SellerProductRequest;
import com.transformersas.marketplace.product.dto.SellerProductResponse;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Publicar y mantener productos (CU-14). La tienda nunca llega por la URL ni por el cuerpo: sale de la sesión del
 * vendedor mediante CurrentActorProvider, que además exige el rol activo VENDEDOR.
 */
@RestController
@RequestMapping("/api/seller/products")
public class SellerProductController {

    private final CurrentActorProvider actor;
    private final SellerProductService service;

    public SellerProductController(CurrentActorProvider actor, SellerProductService service) {
        this.actor = actor;
        this.service = service;
    }

    @GetMapping
    public List<SellerProductResponse> search(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) ProductStatus status) {
        return service.search(actor.storeId(), q, status);
    }

    @GetMapping("/{id}")
    public SellerProductResponse get(@PathVariable Long id) {
        return service.get(actor.storeId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerProductResponse create(@Valid @RequestBody SellerProductRequest request) {
        return service.create(actor.storeId(), request);
    }

    @PutMapping("/{id}")
    public SellerProductResponse update(@PathVariable Long id, @Valid @RequestBody SellerProductRequest request) {
        return service.update(actor.storeId(), id, request);
    }

    @PostMapping("/{id}/publish")
    public SellerProductResponse publish(@PathVariable Long id) {
        return service.publish(actor.storeId(), id);
    }

    @PostMapping("/{id}/pause")
    public SellerProductResponse pause(@PathVariable Long id) {
        return service.pause(actor.storeId(), id);
    }

    @PostMapping("/{id}/reactivate")
    public SellerProductResponse reactivate(@PathVariable Long id) {
        return service.reactivate(actor.storeId(), id);
    }

    @PostMapping("/{id}/retire")
    public SellerProductResponse retire(@PathVariable Long id) {
        return service.retire(actor.storeId(), id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerProductResponse duplicate(@PathVariable Long id) {
        return service.duplicate(actor.storeId(), id);
    }
}
