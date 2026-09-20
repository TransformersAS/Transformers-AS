package com.transformersas.marketplace.catalog;

import com.transformersas.marketplace.product.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Reglas de los atributos y sus valores permitidos (CU-17). Un valor que ya usan productos no se puede quitar. */
@Service
@Transactional
public class AttributeService {

    private final AttributeRepository attributes;
    private final ProductRepository products;

    public AttributeService(AttributeRepository attributes, ProductRepository products) {
        this.attributes = attributes;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<Attribute> getAll() {
        return attributes.findAll(Sort.by("name"));
    }

    public Attribute create(String name) {
        checkNameIsFree(name, null);
        Attribute attribute = new Attribute();
        attribute.setName(name.strip());
        return attributes.save(attribute);
    }

    public Attribute rename(Long id, String name) {
        Attribute attribute = find(id);
        checkNameIsFree(name, id);
        attribute.setName(name.strip());
        return attributes.save(attribute);
    }

    public void delete(Long id) {
        Attribute attribute = find(id);
        boolean inUse = attribute.getValues().stream().anyMatch(value -> products.existsByAttributeValueId(value.getId()));
        if (inUse) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede eliminar un atributo que tiene productos");
        }
        attributes.delete(attribute);
    }

    public Attribute addValue(Long id, String value) {
        Attribute attribute = find(id);
        boolean repeated = attribute.getValues().stream()
                .anyMatch(existing -> existing.getValue().equalsIgnoreCase(value.strip()));
        if (repeated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese valor ya existe en el atributo");
        }
        attribute.getValues().add(new AttributeValue(value.strip()));
        return attributes.save(attribute);
    }

    public Attribute deleteValue(Long id, Long valueId) {
        Attribute attribute = find(id);
        boolean exists = attribute.getValues().stream().anyMatch(value -> value.getId().equals(valueId));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Valor no encontrado");
        }
        if (products.existsByAttributeValueId(valueId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede quitar un valor que tiene productos");
        }
        attribute.getValues().removeIf(value -> value.getId().equals(valueId));
        return attributes.save(attribute);
    }

    private Attribute find(Long id) {
        return attributes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Atributo no encontrado"));
    }

    private void checkNameIsFree(String name, Long ownId) {
        boolean taken = attributes.findByNameIgnoreCase(name.strip())
                .filter(other -> !other.getId().equals(ownId))
                .isPresent();
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un atributo con ese nombre");
        }
    }
}
