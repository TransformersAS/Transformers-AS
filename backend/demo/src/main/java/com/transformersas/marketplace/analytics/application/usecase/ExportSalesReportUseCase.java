package com.transformersas.marketplace.analytics.application.usecase;

import com.transformersas.marketplace.analytics.application.dto.AnalyticsQuery;
import com.transformersas.marketplace.analytics.domain.model.SalesReport;
import com.transformersas.marketplace.analytics.domain.repository.SalesReportExporter;

import org.springframework.stereotype.Service;

/**
 * Exporta a Excel exactamente lo que el vendedor ve con esos periodos y filtros (RF-127). Recalcula el informe con
 * la misma consulta, así el archivo nunca mezcla datos de otra tienda ni de otros filtros.
 */
@Service
public class ExportSalesReportUseCase {

    public record Export(String fileName, byte[] content) {
    }

    private final AnalyzeSalesUseCase analyze;
    private final SalesReportExporter exporter;

    public ExportSalesReportUseCase(AnalyzeSalesUseCase analyze, SalesReportExporter exporter) {
        this.analyze = analyze;
        this.exporter = exporter;
    }

    public Export execute(Long storeId, AnalyticsQuery query) {
        SalesReport report = analyze.execute(storeId, query);
        String name = "analitica-ventas-" + report.current().period().from() + "_" + report.current().period().to()
                + ".xlsx";
        return new Export(name, exporter.export(report));
    }
}
