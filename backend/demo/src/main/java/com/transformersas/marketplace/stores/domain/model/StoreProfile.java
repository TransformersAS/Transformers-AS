package com.transformersas.marketplace.stores.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Datos públicos editables de la tienda (RF-058, RF-059, RF-060): nombre, descripción, contacto y horarios. El nombre
 * es obligatorio y se normaliza (sin espacios sobrantes ni saltos de línea) para que "Mi  Tienda" y "Mi Tienda"
 * cuenten como el mismo nombre. El resto es opcional y el contacto solo se valida si viene (A3). Una entrada inválida
 * lanza BusinessException INVALID con un código estable (A2).
 */
public record StoreProfile(String name, String description, String contactEmail, String contactPhone,
                           String businessHours) {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int EMAIL_MAX = 254;
    public static final int PHONE_MAX = 50;
    public static final int HOURS_MAX = 500;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern CONTROL_EXCEPT_LINE_BREAKS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");
    private static final Pattern PHONE_CHARACTERS = Pattern.compile("\\+?[0-9 ()\\-.]+");

    public StoreProfile {
        name = normalizeName(name);
        description = optionalText(description, DESCRIPTION_MAX, "STORE_DESCRIPTION_TOO_LONG",
                "STORE_DESCRIPTION_INVALID", "La descripción");
        contactEmail = normalizeEmail(contactEmail);
        contactPhone = normalizePhone(contactPhone);
        businessHours = optionalText(businessHours, HOURS_MAX, "STORE_HOURS_TOO_LONG", "STORE_HOURS_INVALID",
                "Los horarios");
    }

    /** Perfil solo con nombre y descripción, sin contacto ni horarios. */
    public StoreProfile(String name, String description) {
        this(name, description, null, null, null);
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

    private static String optionalText(String raw, int max, String tooLongCode, String invalidCode, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > max) {
            throw BusinessException.invalid(tooLongCode, label + " admite hasta " + max + " caracteres");
        }
        if (CONTROL_EXCEPT_LINE_BREAKS.matcher(value).find()) {
            throw BusinessException.invalid(invalidCode, label + " contiene caracteres no válidos");
        }
        return value;
    }

    private static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > EMAIL_MAX || !EMAIL.matcher(value).matches()) {
            throw BusinessException.invalid("STORE_CONTACT_EMAIL_INVALID", "El correo de contacto no es válido");
        }
        return value;
    }

    private static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        long digits = value.chars().filter(Character::isDigit).count();
        if (value.length() > PHONE_MAX || !PHONE_CHARACTERS.matcher(value).matches() || digits < 7 || digits > 15) {
            throw BusinessException.invalid("STORE_CONTACT_PHONE_INVALID",
                    "El teléfono de contacto no es válido: use entre 7 y 15 dígitos");
        }
        return value;
    }
}
