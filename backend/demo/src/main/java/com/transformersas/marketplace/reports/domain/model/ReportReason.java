package com.transformersas.marketplace.reports.domain.model;

import java.util.Optional;

/**
 * Motivos que el sistema ofrece para reportar contenido (RF-146). Los marcados como problema de compra
 * (RF-146/A9) no se pueden radicar aquí: se atienden por reclamaciones y devoluciones. Se listan igual para que el
 * formulario oriente al usuario, y el servicio los rechaza con 422 como respaldo.
 */
public enum ReportReason {
    CONTENIDO_INAPROPIADO("Contenido inapropiado u ofensivo", false),
    PRODUCTO_PROHIBIDO("Producto prohibido o restringido", false),
    INFORMACION_ENGANOSA("Información engañosa o falsa", false),
    POSIBLE_FRAUDE("Posible fraude o estafa", false),
    PROPIEDAD_INTELECTUAL("Infracción de propiedad intelectual", false),
    SPAM("Spam o publicación repetida", false),
    OTRO("Otro motivo", false),
    PRODUCTO_NO_RECIBIDO("No recibí mi pedido", true),
    PRODUCTO_DEFECTUOSO("El producto llegó defectuoso o distinto", true),
    REEMBOLSO_O_DEVOLUCION("Quiero un reembolso o devolución", true);

    private final String label;
    private final boolean purchaseProblem;

    ReportReason(String label, boolean purchaseProblem) {
        this.label = label;
        this.purchaseProblem = purchaseProblem;
    }

    public String label() {
        return label;
    }

    public boolean purchaseProblem() {
        return purchaseProblem;
    }

    public static Optional<ReportReason> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (ReportReason reason : values()) {
            if (reason.name().equals(code.strip())) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
