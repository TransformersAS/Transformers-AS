package com.transformersas.marketplace.returns.infrastructure.web.controller;

import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.shared.error.BusinessExceptionHandler.CodedApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce {@link ReturnException} al formato de error con código; solo aplica a los controladores de devoluciones. */
@RestControllerAdvice(assignableTypes = {BuyerReturnController.class, SellerReturnController.class})
class ReturnExceptionHandler {

    @ExceptionHandler(ReturnException.class)
    ResponseEntity<CodedApiError> returnError(ReturnException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new CodedApiError(exception.status().value(),
                exception.status().getReasonPhrase(), exception.getMessage(), request.getRequestURI(),
                exception.code(), exception.details()));
    }
}
