package com.transformersas.marketplace.returns.application;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Error de las devoluciones (CU-19) con código estable. Existe porque la excepción compartida no distingue 422 (una
 * línea no elegible, A1) del 400 de validación. {@code details} es opcional; para errores de campo lleva
 * {@code {"field": "..."}}.
 */
public class ReturnException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ReturnException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ReturnException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public static ReturnException notFound(String code, String message) {
        return new ReturnException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ReturnException field(String field, String code, String message) {
        return new ReturnException(HttpStatus.BAD_REQUEST, code, message, Map.of("field", field));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
