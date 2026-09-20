package com.transformersas.marketplace.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administración de marcas. Solo el rol ADMIN llega aquí (ver SecurityConfiguration). */
@RestController
@RequestMapping("/api/admin/brands")
public class BrandController {

    public record BrandRequest(@NotBlank @Size(max = 100) String name) {
    }

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @GetMapping
    public List<Brand> getAll() {
        return brandService.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Brand create(@Valid @RequestBody BrandRequest request) {
        return brandService.create(request.name());
    }

    @PutMapping("/{id}")
    public Brand rename(@PathVariable Long id, @Valid @RequestBody BrandRequest request) {
        return brandService.rename(id, request.name());
    }

    @PostMapping("/{id}/activate")
    public Brand activate(@PathVariable Long id) {
        return brandService.setActive(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public Brand deactivate(@PathVariable Long id) {
        return brandService.setActive(id, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        brandService.delete(id);
    }
}
