package com.transformersas.marketplace.shared.files;

import com.transformersas.marketplace.shared.error.BusinessExceptionHandler.CodedApiError;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Spring corta las subidas que superan spring.servlet.multipart.max-file-size antes de llegar al controlador. Esta
 * respuesta usa el mismo formato de error y el mismo código que ImageValidator (IMAGE_TOO_LARGE), con 413.
 */
@RestControllerAdvice
public class UploadSizeExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CodedApiError> tooLarge(HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        return ResponseEntity.status(status).body(new CodedApiError(status.value(), status.getReasonPhrase(),
                "El archivo supera el tamaño máximo permitido", request.getRequestURI(), "IMAGE_TOO_LARGE", null));
    }
}
