package com.transformersas.marketplace.returns.domain.port;

/**
 * Estado de la recogida de una devolución en logística (CU-25), solo lectura. Tras el tercer intento fallido de
 * recogida logística deja de pedir recogidas y no avisa a devoluciones (D1): la devolución sigue Aprobada y esto lo
 * deriva para que el comprador y el vendedor lo vean.
 */
public interface PickupStatusReader {

    /** Verdadero si logística dejó de pedir recogidas de esta devolución. Falso si no hay envío de retorno todavía. */
    boolean pickupBlocked(Long returnId);
}
