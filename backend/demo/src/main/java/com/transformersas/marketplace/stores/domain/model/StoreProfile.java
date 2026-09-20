package com.transformersas.marketplace.stores.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.regex.Pattern;

/**
 * Datos públicos editables de la tienda (RF-058). El nombre es obligatorio y se normaliza (sin espacios sobrantes
 * ni saltos de línea) para que "Mi  Tienda" y "Mi Tienda" cuenten como el mismo nombre; la descripción es opcional.
 * Una entrada inválida lanza BusinessException INVALID con un código estable (A2).
 */
public record StoreProfile(String name, String description) {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 1000;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern CONTROL_EXCEPT_LINE_BREAKS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public StoreProfile {
        name = normalizeName(name);
        description = normalizeDescription(description);
    }

    private static String normalizeName(String raw) {
        String value = raw == null ? "" : WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        if (value.isEmpty()) {
            throw BusinessException.invalid("STORE_NAME_REQUIRED", "El nombre de la tienda es obligatorio");
        }
        if (value.length() > NAME_MAX) {
            throw BusinessException.invalid("STORE_NAME_TOO_LONG",
                    "El nombre de la tienda admite hasta " + NAME_MAX + " caracteres");
        }
        if (CONTROL.matcher(value).find()) {
            throw BusinessException.invalid("STORE_NAME_INVALID", "El nombre de la tienda contiene caracteres no válidos");
        }
        return value;
    }

    private static String normalizeDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > DESCRIPTION_MAX) {
            throw BusinessException.invalid("STORE_DESCRIPTION_TOO_LONG",
                    "La descripción admite hasta " + DESCRIPTION_MAX + " caracteres");
        }
        if (CONTROL_EXCEPT_LINE_BREAKS.matcher(value).find()) {
            throw BusinessException.invalid("STORE_DESCRIPTION_INVALID", "La descripción contiene caracteres no válidos");
        }
        return value;
    }
}
