package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Catálogo tomado de logistics.shipping-methods (por defecto STANDARD y EXPRESS, los del checkout actual). El contrato
 * del proveedor logístico aún no define una operación para listar métodos; cuando exista, esta clase se reemplaza sin
 * cambiar a quien consulta el puerto. Un nombre inválido o una lista vacía impiden el arranque.
 */
@Component
public class ConfiguredShippingMethodCatalog implements ShippingMethodCatalog {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Z][A-Z_]{0,29}");

    private final List<String> methods;

    public ConfiguredShippingMethodCatalog(
            @Value("${logistics.shipping-methods:STANDARD,EXPRESS}") List<String> configured) {
        this.methods = configured.stream().map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
        if (methods.isEmpty()) {
            throw new IllegalStateException("logistics.shipping-methods debe definir al menos un método de envío");
        }
        methods.forEach(name -> {
            if (!VALID_NAME.matcher(name).matches()) {
                throw new IllegalStateException("Método de envío inválido en logistics.shipping-methods: " + name);
            }
        });
    }

    @Override
    public List<String> availableMethods() {
        return methods;
    }
}
