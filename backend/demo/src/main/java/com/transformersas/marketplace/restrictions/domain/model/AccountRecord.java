package com.transformersas.marketplace.restrictions.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Antecedente administrativo de una cuenta (precondición 3, pasos 5 y 6): una reclamación que soporte resolvió con
 * reembolso al comprador (la responsabilidad quedó en la tienda de esta cuenta), una decisión de moderación que
 * ocultó o retiró contenido de la tienda de esta cuenta, o una restricción anterior sobre la cuenta. Una reclamación
 * o un reporte recién creados no son antecedentes.
 *
 * @param referenceId id de la reclamación, del caso de moderación o de la restricción anterior
 * @param subject     producto, contenido o alcance afectado
 * @param decision    REFUND_GRANTED, OCULTAR_TEMPORALMENTE, RETIRAR, o el estado final de la restricción anterior
 * @param amount      monto reembolsado; nulo salvo en reclamaciones
 */
public record AccountRecord(Type type, Long referenceId, LocalDateTime decidedAt, String subject, String decision,
                            String detail, BigDecimal amount) {

    public enum Type { CLAIM, MODERATION, RESTRICTION }
}
