package com.transformersas.marketplace.shared.web;

/**
 * Marca un request JSON que rechaza propiedades desconocidas (400). Spring Boot las ignora por defecto;
 * la marca evita cambiar el comportamiento de los endpoints existentes.
 */
public interface StrictJsonRequest {
}
