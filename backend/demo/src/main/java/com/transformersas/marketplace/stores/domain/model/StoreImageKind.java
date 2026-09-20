package com.transformersas.marketplace.stores.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.Locale;

/** Tipos de imagen de una tienda; solo hay una de cada tipo. */
public enum StoreImageKind {
    LOGO, PORTADA;

    /** Interpreta el segmento de la ruta sin distinguir mayúsculas ("logo", "Portada"). */
    public static StoreImageKind fromPath(String value) {
        for (StoreImageKind kind : values()) {
            if (kind.name().equals(value == null ? null : value.toUpperCase(Locale.ROOT))) {
                return kind;
            }
        }
        throw BusinessException.invalid("STORE_IMAGE_KIND_INVALID", "El tipo de imagen debe ser logo o portada");
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }
}
