package com.transformersas.marketplace.analytics.infrastructure.web.controller;

import com.transformersas.marketplace.analytics.application.dto.AnalyticsQuery;
import com.transformersas.marketplace.analytics.application.usecase.AnalyzeSalesUseCase;
import com.transformersas.marketplace.analytics.application.usecase.ExportSalesReportUseCase;
import com.transformersas.marketplace.analytics.application.usecase.GetAnalyticsOptionsUseCase;
import com.transformersas.marketplace.analytics.domain.repository.SalesReportExporter.ExportFailedException;
import com.transformersas.marketplace.analytics.infrastructure.web.response.AnalyticsOptionsResponse;
import com.transformersas.marketplace.analytics.infrastructure.web.response.SalesReportResponse;
import com.transformersas.marketplace.shared.error.BusinessExceptionHandler.CodedApiError;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Analítica de ventas y rentabilidad (CU-26). La tienda sale de la sesión del vendedor (CurrentActorProvider exige el
 * rol activo VENDEDOR), nunca de la petición: cada vendedor solo ve y exporta lo de su tienda (RF-127, RNF-003).
 * Fechas en formato ISO (yyyy-MM-dd).
 */
@RestController
@RequestMapping("/api/seller/analytics")
public class SellerAnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(SellerAnalyticsController.class);
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CurrentActorProvider actor;
    private final AnalyzeSalesUseCase analyze;
    private final ExportSalesReportUseCase export;
    private final GetAnalyticsOptionsUseCase options;

    public SellerAnalyticsController(CurrentActorProvider actor, AnalyzeSalesUseCase analyze,
                                     ExportSalesReportUseCase export, GetAnalyticsOptionsUseCase options) {
        this.actor = actor;
        this.analyze = analyze;
        this.export = export;
        this.options = options;
    }

    @GetMapping
    public SalesReportResponse analyze(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String category) {
        return SalesReportResponse.from(analyze.execute(actor.storeId(),
                new AnalyticsQuery(from, to, compareFrom, compareTo, productId, category)));
    }

    @GetMapping("/options")
    public AnalyticsOptionsResponse options() {
        return AnalyticsOptionsResponse.from(options.execute(actor.storeId()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String category) {
        var file = export.execute(actor.storeId(),
                new AnalyticsQuery(from, to, compareFrom, compareTo, productId, category));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(XLSX)
                .body(file.content());
    }

    /** A6: el error se informa con un código estable; la pantalla conserva los resultados y permite reintentar. */
    @ExceptionHandler(ExportFailedException.class)
    public ResponseEntity<CodedApiError> exportFailed(ExportFailedException failure, HttpServletRequest request) {
        log.error("Falló la exportación de la analítica", failure);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(new CodedApiError(status.value(), status.getReasonPhrase(),
                "No se pudo generar el archivo Excel. Intenta exportar de nuevo.", request.getRequestURI(),
                "EXPORT_FAILED", null));
    }
}
