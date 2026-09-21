package com.transformersas.marketplace.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce BusinessException al formato de error existente (status, error, message, path) más code y details. */
@RestControllerAdvice
public class BusinessExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodedApiError(int status, String error, String message, String path, String code, Object details) {
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<CodedApiError> business(BusinessException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_GATEWAY -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new CodedApiError(status.value(), status.getReasonPhrase(),
                exception.getMessage(), request.getRequestURI(), exception.code(), exception.details()));
    }
}
