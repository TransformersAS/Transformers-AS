package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.reports.application.ReportException;
import com.transformersas.marketplace.shared.error.BusinessExceptionHandler.CodedApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce {@link ReportException} al formato de error con código; solo aplica a los controladores de CU-20. */
@RestControllerAdvice(assignableTypes = {ReportController.class, SupportEvidenceController.class})
class ReportExceptionHandler {

    @ExceptionHandler(ReportException.class)
    ResponseEntity<CodedApiError> report(ReportException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new CodedApiError(exception.status().value(),
                exception.status().getReasonPhrase(), exception.getMessage(), request.getRequestURI(),
                exception.code(), exception.details()));
    }
}
