package com.transformersas.marketplace.catalog;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Reglas de los atributos y sus valores permitidos (CU-17). Los productos todavía no usan
 * atributos, así que se pueden eliminar libremente; cuando existan habrá que validar su uso.
 */
@Service
@Transactional
public class AttributeService {

    private final AttributeRepository attributes;

    public AttributeService(AttributeRepository attributes) {
        this.attributes = attributes;
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
        attributes.delete(find(id));
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
        boolean removed = attribute.getValues().removeIf(value -> value.getId().equals(valueId));
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Valor no encontrado");
        }
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
