package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;

/**
 * Puerto hacia el módulo de pedidos: logística nunca toca las tablas de pedidos, le pide que aplique la
 * actualización. Lo implementa orders (que ya depende de logística), así no hay dependencia circular.
 */
public interface OrderTrackingPort {

    /**
     * Aplica la actualización al pedido dentro de la transacción en curso (historial, auditoría y notificaciones
     * incluidos) y devuelve su efecto. Nunca retrocede el estado: en ese caso devuelve OUT_OF_ORDER.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.TrackingConflictException si otra petición
     *         cambió el pedido a la vez (el llamador reintenta)
     */
    TrackingOutcome apply(Long orderId, TrackingUpdate update);
}
