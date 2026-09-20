package com.transformersas.marketplace.stores.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.regex.Pattern;

/**
 * Política de la tienda (RF-060): plazo de devolución en días y texto libre. Aquí solo se validan la forma y los
 * topes; el mínimo obligatorio del marketplace (A5) lo aplica el caso de uso con StorePolicyRules.
 */
public record StorePolicy(int returnWindowDays, String text) {

    public static final int DEFAULT_RETURN_WINDOW_DAYS = 30;
    public static final int MAX_RETURN_WINDOW_DAYS = 365;
    public static final int TEXT_MAX = 2000;

    public static final StorePolicy DEFAULT = new StorePolicy(DEFAULT_RETURN_WINDOW_DAYS, null);

    private static final Pattern CONTROL_EXCEPT_LINE_BREAKS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public StorePolicy {
        if (returnWindowDays < 1 || returnWindowDays > MAX_RETURN_WINDOW_DAYS) {
            throw BusinessException.invalid("STORE_RETURN_WINDOW_INVALID",
                    "El plazo de devolución debe estar entre 1 y " + MAX_RETURN_WINDOW_DAYS + " días");
        }
        if (text == null || text.isBlank()) {
            text = null;
        } else {
            text = text.strip();
            if (text.length() > TEXT_MAX) {
                throw BusinessException.invalid("STORE_POLICY_TEXT_TOO_LONG",
                        "El texto de la política admite hasta " + TEXT_MAX + " caracteres");
            }
            if (CONTROL_EXCEPT_LINE_BREAKS.matcher(text).find()) {
                throw BusinessException.invalid("STORE_POLICY_TEXT_INVALID",
                        "El texto de la política contiene caracteres no válidos");
            }
        }
    }
}
