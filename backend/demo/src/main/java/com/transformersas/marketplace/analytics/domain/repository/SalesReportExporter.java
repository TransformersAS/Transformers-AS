package com.transformersas.marketplace.analytics.domain.repository;

import com.transformersas.marketplace.analytics.domain.model.SalesReport;

/** Genera el archivo del reporte (RF-127) con los periodos, filtros e indicadores del informe recibido. */
public interface SalesReportExporter {

    /** Lanza {@link ExportFailedException} si no puede crear el archivo (A6). */
    byte[] export(SalesReport report);

    class ExportFailedException extends RuntimeException {
        public ExportFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
