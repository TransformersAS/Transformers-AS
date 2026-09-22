package com.transformersas.marketplace.logistics.domain.model;

/** Un método de retorno que el servicio logístico ofrece para una devolución (RF-109): un código estable y su nombre. */
public record ReturnMethod(String code, String label) {
}
