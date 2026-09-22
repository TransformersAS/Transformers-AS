package com.transformersas.marketplace.returns.application.event;

import java.math.BigDecimal;

/**
 * Una reclamación (CU-13) se resolvió y exige que el comprador devuelva el producto. Devoluciones lo escucha y deja la
 * devolución de esa línea Aprobada, sin plazo y con la referencia a la reclamación; si la línea la había rechazado el
 * vendedor, la reabre.
 *
 * <p>PROVISIONAL: vive en devoluciones porque reclamaciones todavía no publica este evento ni tiene la marca
 * requiresReturn (pendiente de acuerdo con quien hizo CU-13). Cuando la acepte, la clase pasa a su paquete, que es
 * quien la publica: devoluciones la importa de allí y nunca al revés. {@code agreedRefund} es el reembolso acordado en
 * la reclamación, o nulo para el de la línea completa.
 */
public record ClaimResolvedRequiringReturn(Long claimId, Long orderId, Long productId, Long buyerAccountId,
                                           String description, BigDecimal agreedRefund) {
}
