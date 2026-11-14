package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.promotions.dto.PromotionRequest;
import com.transformersas.marketplace.promotions.dto.PromotionResponse;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cupones de la tienda del vendedor (CU-16). Como en CU-14 y CU-15, la tienda sale de la sesión mediante
 * CurrentActorProvider, que además exige el rol activo VENDEDOR.
 */
@RestController
@RequestMapping("/api/seller/promotions")
public class SellerPromotionController {

    private final CurrentActorProvider actor;
    private final SellerPromotionService service;

    public SellerPromotionController(CurrentActorProvider actor, SellerPromotionService service) {
        this.actor = actor;
        this.service = service;
    }

    @GetMapping
    public List<PromotionResponse> list() {
        return service.list(actor.storeId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionResponse create(@Valid @RequestBody PromotionRequest request) {
        return service.create(actor.storeId(), request);
    }

    @PutMapping("/{id}")
    public PromotionResponse update(@PathVariable Long id, @Valid @RequestBody PromotionRequest request) {
        return service.update(actor.storeId(), id, request);
    }

    @PostMapping("/{id}/activate")
    public PromotionResponse activate(@PathVariable Long id) {
        return service.activate(actor.storeId(), id);
    }

    @PostMapping("/{id}/deactivate")
    public PromotionResponse deactivate(@PathVariable Long id) {
        return service.deactivate(actor.storeId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(actor.storeId(), id);
    }
}
