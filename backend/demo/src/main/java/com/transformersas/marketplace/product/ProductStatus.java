package com.transformersas.marketplace.product;

/** Ciclo de vida de una publicación (CU-14). Solo ACTIVE se puede comprar. */
public enum ProductStatus {
    /** Borrador: el vendedor todavía lo prepara y nadie más lo ve. */
    DRAFT,
    ACTIVE,
    /** Pausado: no se puede comprar, pero se puede reactivar. */
    PAUSED,
    /** Retirado: definitivo, ya no se puede editar ni reactivar. */
    RETIRED
}
