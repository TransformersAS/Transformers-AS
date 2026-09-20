package com.transformersas.marketplace.catalog;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Reglas de las marcas (CU-17). Los productos todavía no guardan su marca, así que una marca
 * se puede desactivar o eliminar libremente; cuando exista ese vínculo habrá que validar su uso.
 */
@Service
@Transactional
public class BrandService {

    private final BrandRepository brands;

    public BrandService(BrandRepository brands) {
        this.brands = brands;
    }

    @Transactional(readOnly = true)
    public List<Brand> getAll() {
        return brands.findAll(Sort.by("name"));
    }

    public Brand create(String name) {
        checkNameIsFree(name, null);
        Brand brand = new Brand();
        brand.setName(name.strip());
        return brands.save(brand);
    }

    public Brand rename(Long id, String name) {
        Brand brand = find(id);
        checkNameIsFree(name, id);
        brand.setName(name.strip());
        return brands.save(brand);
    }

    public Brand setActive(Long id, boolean active) {
        Brand brand = find(id);
        brand.setActive(active);
        return brands.save(brand);
    }

    public void delete(Long id) {
        brands.delete(find(id));
    }

    private Brand find(Long id) {
        return brands.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Marca no encontrada"));
    }

    private void checkNameIsFree(String name, Long ownId) {
        boolean taken = brands.findByNameIgnoreCase(name.strip())
                .filter(other -> !other.getId().equals(ownId))
                .isPresent();
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una marca con ese nombre");
        }
    }
}
