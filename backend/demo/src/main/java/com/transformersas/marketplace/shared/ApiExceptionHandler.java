package com.transformersas.marketplace.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(int status, String error, String message, String path) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted().distinct().collect(java.util.stream.Collectors.joining("; "));
        return response(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request mal formado", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> business(ResponseStatusException exception, HttpServletRequest request) {
        return response(HttpStatus.valueOf(exception.getStatusCode().value()), exception.getReason(), request);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ApiError> concurrency(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "Otro usuario modificó el recurso al mismo tiempo; intente de nuevo",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "La operación viola una restricción de datos", request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(),
                message, request.getRequestURI()));
    }
}
