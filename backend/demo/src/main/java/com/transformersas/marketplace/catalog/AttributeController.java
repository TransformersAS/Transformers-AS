package com.transformersas.marketplace.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administración de atributos y valores permitidos. Solo el rol ADMIN llega aquí (ver SecurityConfiguration). */
@RestController
@RequestMapping("/api/admin/attributes")
public class AttributeController {

    public record NameRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record ValueRequest(@NotBlank @Size(max = 100) String value) {
    }

    private final AttributeService attributeService;

    public AttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @GetMapping
    public List<Attribute> getAll() {
        return attributeService.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Attribute create(@Valid @RequestBody NameRequest request) {
        return attributeService.create(request.name());
    }

    @PutMapping("/{id}")
    public Attribute rename(@PathVariable Long id, @Valid @RequestBody NameRequest request) {
        return attributeService.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        attributeService.delete(id);
    }

    @PostMapping("/{id}/values")
    @ResponseStatus(HttpStatus.CREATED)
    public Attribute addValue(@PathVariable Long id, @Valid @RequestBody ValueRequest request) {
        return attributeService.addValue(id, request.value());
    }

    @DeleteMapping("/{id}/values/{valueId}")
    public Attribute deleteValue(@PathVariable Long id, @PathVariable Long valueId) {
        return attributeService.deleteValue(id, valueId);
    }
}
