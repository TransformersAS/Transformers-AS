package com.transformersas.marketplace.stores.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

import java.util.List;

/**
 * Configuración completa de la tienda (RF-058 a RF-061): reemplaza el perfil, la política y los métodos de envío.
 * Los datos opcionales que no se envían quedan vacíos y, sin returnWindowDays, se usa el plazo por defecto.
 * shippingMethods es obligatorio: la tienda ofrece siempre al menos un método. version es la que devolvió la
 * consulta y es obligatoria al guardar; la vista previa la ignora. La forma de cada dato la valida el dominio.
 */
public record StoreSettingsRequest(
        String name,
        String description,
        String contactEmail,
        String contactPhone,
        String businessHours,
        Integer returnWindowDays,
        String policyText,
        List<String> shippingMethods,
        Long version
) implements StrictJsonRequest {
}
