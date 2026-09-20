package com.transformersas.marketplace.catalog;

import com.transformersas.marketplace.product.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Reglas de las marcas (CU-17). Una marca que ya usan productos no se puede desactivar ni eliminar. */
@Service
@Transactional
public class BrandService {

    private final BrandRepository brands;
    private final ProductRepository products;

    public BrandService(BrandRepository brands, ProductRepository products) {
        this.brands = brands;
        this.products = products;
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
        if (!active && products.existsByBrandId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede desactivar una marca que tiene productos");
        }
        brand.setActive(active);
        return brands.save(brand);
    }

    public void delete(Long id) {
        Brand brand = find(id);
        if (products.existsByBrandId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede eliminar una marca que tiene productos");
        }
        brands.delete(brand);
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
