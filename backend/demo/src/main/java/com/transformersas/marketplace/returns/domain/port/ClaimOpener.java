package com.transformersas.marketplace.returns.domain.port;

/**
 * Abre una reclamación de compra (CU-13). Devoluciones no depende del módulo de reclamaciones: solo de este puerto, que
 * implementa un adaptador. La dependencia va en un solo sentido (returns → claims) porque claims no conoce returns.
 */
public interface ClaimOpener {

    /**
     * Abre la reclamación a nombre del comprador sobre un producto de un pedido suyo y devuelve su id. Puede fallar, por
     * ejemplo si ya hay una reclamación abierta para ese producto; quien la llame decide qué hacer.
     */
    Long open(Long buyerAccountId, Long orderId, Long productId, String description);
}
