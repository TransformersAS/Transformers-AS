package com.transformersas.marketplace.shared.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Acceso al identificador de correlación (RNF-038) de la petición o tarea en curso. */
public final class CorrelationContext {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    // Solo se acepta un formato seguro para logs y cabeceras (sin saltos de línea ni espacios).
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationContext() {
    }

    /** Devuelve el id de la petición actual o genera uno nuevo si no hay contexto (tareas sin petición). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : newId();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Devuelve el valor si es un id válido; de lo contrario null. */
    public static String sanitize(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : null;
    }

    /** Ejecuta la tarea con el id indicado en el MDC (hilos asíncronos) y restaura el estado previo. */
    public static void runWith(String correlationId, Runnable task) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, sanitize(correlationId) != null ? correlationId : newId());
        try {
            task.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
