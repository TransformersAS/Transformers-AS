package com.transformersas.marketplace.suspensions.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Antecedente negativo confirmado de una tienda (precondición 3, RF-168). Solo hay dos fuentes, ambas ya decididas
 * por otra persona: una reclamación que soporte resolvió con reembolso al comprador (la responsabilidad quedó en el
 * vendedor) o una decisión de moderación que ocultó o retiró contenido de la tienda. Una reclamación o un reporte
 * recién creados no son antecedentes.
 *
 * @param referenceId id de la reclamación o del caso de moderación
 * @param subject     producto o contenido afectado
 * @param decision    REFUND_GRANTED, OCULTAR_TEMPORALMENTE o RETIRAR
 * @param amount      monto reembolsado; nulo en moderación
 */
public record StoreRecord(Type type, Long referenceId, LocalDateTime decidedAt, String subject, String decision,
                          String detail, BigDecimal amount) {

    public enum Type { CLAIM, MODERATION }
}
