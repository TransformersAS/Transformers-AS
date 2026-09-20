package com.transformersas.marketplace.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Administración de categorías. Solo el rol ADMIN llega aquí (ver SecurityConfiguration). */
@RestController
@RequestMapping("/api/admin/categories")
public class CategoryController {

    /** parentId nulo = categoría principal. */
    public record CategoryRequest(@NotBlank @Size(max = 100) String name, Long parentId) {
    }

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryService.CategoryNode> getTree() {
        return categoryService.getTree();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Category create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request.name(), request.parentId());
    }

    @PutMapping("/{id}")
    public Category update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request.name(), request.parentId());
    }

    @PostMapping("/{id}/activate")
    public Category activate(@PathVariable Long id) {
        return categoryService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public Category deactivate(@PathVariable Long id) {
        return categoryService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }
}
