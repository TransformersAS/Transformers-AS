package com.transformersas.marketplace.catalog;

import com.transformersas.marketplace.product.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/** Reglas de las categorías y subcategorías (CU-06). */
@Service
@Transactional
public class CategoryService {

    /** Categoría con sus subcategorías, para mostrar la estructura como un árbol. */
    public record CategoryNode(Long id, String name, boolean active, List<CategoryNode> children) {
    }

    private final CategoryRepository categories;
    private final ProductRepository products;

    public CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<CategoryNode> getTree() {
        List<Category> all = categories.findAll(Sort.by("name"));
        return childrenOf(null, all);
    }

    public Category create(String name, Long parentId) {
        checkParentIsUsable(parentId);
        checkNameIsFree(name, parentId, null);
        Category category = new Category();
        category.setName(name.strip());
        category.setParentId(parentId);
        return categories.save(category);
    }

    /** Cambia el nombre y/o mueve la categoría a otro padre (reorganizar la jerarquía). */
    public Category update(Long id, String name, Long parentId) {
        Category category = find(id);
        boolean renamed = !category.getName().equalsIgnoreCase(name.strip());
        if (renamed && hasProducts(category)) {
            throw conflict("No se puede renombrar una categoría que tiene productos");
        }
        if (!Objects.equals(category.getParentId(), parentId)) {
            checkParentIsUsable(parentId);
            checkNoCycle(category, parentId);
        }
        checkNameIsFree(name, parentId, id);
        category.setName(name.strip());
        category.setParentId(parentId);
        return categories.save(category);
    }

    public Category activate(Long id) {
        Category category = find(id);
        checkParentIsUsable(category.getParentId());
        category.setActive(true);
        return categories.save(category);
    }

    public Category deactivate(Long id) {
        Category category = find(id);
        if (categories.existsByParentIdAndActiveTrue(id)) {
            throw conflict("Desactiva primero sus subcategorías activas");
        }
        if (hasProducts(category)) {
            throw conflict("No se puede desactivar una categoría que tiene productos");
        }
        category.setActive(false);
        return categories.save(category);
    }

    public void delete(Long id) {
        Category category = find(id);
        if (categories.existsByParentId(id)) {
            throw conflict("No se puede eliminar una categoría que tiene subcategorías");
        }
        if (hasProducts(category)) {
            throw conflict("No se puede eliminar una categoría que tiene productos");
        }
        categories.delete(category);
    }

    // ---------- Métodos auxiliares ----------

    private List<CategoryNode> childrenOf(Long parentId, List<Category> all) {
        return all.stream()
                .filter(category -> Objects.equals(category.getParentId(), parentId))
                .map(category -> new CategoryNode(category.getId(), category.getName(), category.isActive(),
                        childrenOf(category.getId(), all)))
                .toList();
    }

    private Category find(Long id) {
        return categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoría no encontrada"));
    }

    /** El padre (si hay) debe existir y estar activo. */
    private void checkParentIsUsable(Long parentId) {
        if (parentId == null) {
            return;
        }
        Category parent = categories.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La categoría padre no existe"));
        if (!parent.isActive()) {
            throw conflict("La categoría padre está inactiva");
        }
    }

    /** Dos categorías hermanas no pueden llamarse igual (sin distinguir mayúsculas). */
    private void checkNameIsFree(String name, Long parentId, Long ownId) {
        boolean taken = categories.findByParentIdAndNameIgnoreCase(parentId, name.strip())
                .filter(other -> !other.getId().equals(ownId))
                .isPresent();
        if (taken) {
            throw conflict("Ya existe una categoría con ese nombre en este nivel");
        }
    }

    /** Sube desde el nuevo padre hasta la raíz: si pasa por la categoría movida, se crearía un ciclo. */
    private void checkNoCycle(Category category, Long newParentId) {
        Long ancestorId = newParentId;
        while (ancestorId != null) {
            if (ancestorId.equals(category.getId())) {
                throw conflict("No se puede mover una categoría dentro de sí misma o de sus subcategorías");
            }
            ancestorId = find(ancestorId).getParentId();
        }
    }

    /** Los productos guardan la categoría como texto, así que se comparan por nombre. */
    private boolean hasProducts(Category category) {
        return products.existsByCategoryIgnoreCase(category.getName());
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
