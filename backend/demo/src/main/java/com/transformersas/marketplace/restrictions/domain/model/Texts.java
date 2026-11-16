package com.transformersas.marketplace.restrictions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.users.domain.model.Role;

import java.util.Map;

/**
 * Validación de los textos que escribe el administrador (motivos, justificaciones, mensajes). El error indica el
 * campo para que la pantalla lo marque junto al control correspondiente (RNF-027).
 */
public final class Texts {

    private Texts() {
    }

    /** Devuelve el texto sin espacios sobrantes o lanza INVALID con {@code code} y el campo. */
    public static String required(String value, int min, int max, String code, String field, String what) {
        String text = value == null ? "" : value.strip();
        if (text.length() < min || text.length() > max) {
            throw new BusinessException(BusinessException.Kind.INVALID, code,
                    what + " debe tener entre " + min + " y " + max + " caracteres", Map.of("field", field));
        }
        return text;
    }

    /** Nombre del rol para textos que lee una persona (antecedentes y notificaciones), no el valor del enum. */
    public static String roleLabel(Role role) {
        return switch (role) {
            case COMPRADOR -> "Comprador";
            case VENDEDOR -> "Vendedor";
            case ADMIN -> "Administrador";
            case SOPORTE -> "Soporte";
        };
    }
}
