package com.transformersas.marketplace.reports.domain.port;

import java.util.Map;

/**
 * Vista de solo lectura del contenido reportado (RF-157). {@code ownerId} es opcional: cuando el
 * módulo dueño no lo conoce no se puede pedir información al propietario.
 */
public record ContentSnapshot(String title, String text, String ownerId, Map<String, String> attributes) {
}
