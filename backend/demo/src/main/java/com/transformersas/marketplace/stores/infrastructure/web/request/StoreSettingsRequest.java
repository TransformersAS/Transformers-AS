package com.transformersas.marketplace.stores.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

/**
 * Configuración completa de la tienda (RF-058 a RF-060): reemplaza el perfil y la política. Los datos opcionales que
 * no se envían quedan vacíos y, sin returnWindowDays, se usa el plazo por defecto. version es la que devolvió la
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
        Long version
) implements StrictJsonRequest {
}
