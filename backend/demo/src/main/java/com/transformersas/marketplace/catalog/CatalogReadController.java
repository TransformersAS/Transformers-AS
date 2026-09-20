package com.transformersas.marketplace.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consulta de la estructura del catálogo para cualquier usuario con sesión (por ejemplo, un vendedor que va a
 * publicar un producto). Solo lectura: los cambios los hace el administrador en /api/admin/**.
 */
@RestController
@RequestMapping("/api")
public class CatalogReadController {

    private final CategoryService categoryService;
    private final BrandService brandService;
    private final AttributeService attributeService;

    public CatalogReadController(CategoryService categoryService, BrandService brandService,
                                 AttributeService attributeService) {
        this.categoryService = categoryService;
        this.brandService = brandService;
        this.attributeService = attributeService;
    }

    @GetMapping("/categories")
    public List<CategoryService.CategoryNode> categories() {
        return categoryService.getActiveTree();
    }

    @GetMapping("/brands")
    public List<Brand> brands() {
        return brandService.getActive();
    }

    @GetMapping("/attributes")
    public List<Attribute> attributes() {
        return attributeService.getAll();
    }
}
