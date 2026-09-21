package com.transformersas.marketplace.returns.domain.model;

/** Cómo nace una devolución: la pide el comprador (con plazo) o la origina una reclamación de CU-13 (sin plazo). */
public enum ReturnOrigin {
    BUYER,
    CLAIM
}
