package com.transformersas.marketplace.returns.domain.eligibility;

/** Por qué una línea no se puede devolver (A1): un código estable y un mensaje para el comprador. */
public record Ineligibility(String code, String message) {
}
