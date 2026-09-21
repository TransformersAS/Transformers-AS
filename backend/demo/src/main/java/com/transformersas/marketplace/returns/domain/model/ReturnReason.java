package com.transformersas.marketplace.returns.domain.model;

import java.util.Optional;

/**
 * Motivos que el sistema ofrece al solicitar una devolución (RF-049). La lista no viene definida en el requisito: es
 * un supuesto razonable, documentado, y agregar uno no exige migrar la base porque reason_code no lleva CHECK.
 */
public enum ReturnReason {
    DEFECTIVE("El producto llegó defectuoso"),
    NOT_AS_DESCRIBED("No es como se describía"),
    DAMAGED_IN_TRANSIT("Se dañó en el envío"),
    WRONG_ITEM("Recibí otro producto"),
    CHANGED_MIND("Ya no lo quiero"),
    OTHER("Otro motivo");

    private final String label;

    ReturnReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<ReturnReason> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (ReturnReason reason : values()) {
            if (reason.name().equals(code.strip())) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
