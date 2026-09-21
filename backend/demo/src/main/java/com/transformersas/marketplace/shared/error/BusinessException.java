package com.transformersas.marketplace.shared.error;

/**
 * Error de negocio con código estable. El dominio decide el tipo (Kind); la capa web lo traduce a HTTP.
 * details es opcional y se serializa en el cuerpo del error (p. ej. las novedades abiertas).
 */
public class BusinessException extends RuntimeException {

    public enum Kind { INVALID, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, BAD_GATEWAY }

    private final Kind kind;
    private final String code;
    private final transient Object details;

    public BusinessException(Kind kind, String code, String message, Object details) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.details = details;
    }

    public static BusinessException invalid(String code, String message) {
        return new BusinessException(Kind.INVALID, code, message, null);
    }

    public static BusinessException unauthenticated(String code, String message) {
        return new BusinessException(Kind.UNAUTHENTICATED, code, message, null);
    }

    public static BusinessException forbidden(String code, String message) {
        return new BusinessException(Kind.FORBIDDEN, code, message, null);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(Kind.NOT_FOUND, code, message, null);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(Kind.CONFLICT, code, message, null);
    }

    public static BusinessException conflict(String code, String message, Object details) {
        return new BusinessException(Kind.CONFLICT, code, message, details);
    }

    public static BusinessException badGateway(String code, String message) {
        return new BusinessException(Kind.BAD_GATEWAY, code, message, null);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
