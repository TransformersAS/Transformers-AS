package com.transformersas.marketplace.reports.application;

import org.springframework.http.HttpStatus;

/**
 * Error de la radicación y consulta de reportes (CU-20) con código estable. Existe porque la excepción
 * compartida no distingue 422 (motivos de compra, tipos sin módulo) del 400 de validación.
 * {@code details} es opcional; para errores de campo lleva {@code {"field": "..."}}.
 */
public class ReportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ReportException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ReportException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public static ReportException field(String field, String code, String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, code, message, java.util.Map.of("field", field));
    }

    public static ReportException notFound(String message) {
        return new ReportException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", message);
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
