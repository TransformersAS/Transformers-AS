package com.transformersas.marketplace.reports.domain.port;

import java.util.List;
import java.util.Optional;

/** Almacén del contenido de las imágenes de evidencia de un reporte (CU-20). */
public interface ReportEvidenceStorage {

    /** Datos de una imagen sin su contenido; {@code ordinal} empieza en 1 dentro del reporte. */
    record EvidenceSummary(int ordinal, String fileName, String contentType, long sizeBytes, String sha256) {
    }

    /** Imagen completa, para servirla. */
    record EvidenceContent(EvidenceSummary summary, byte[] data) {
    }

    /** Guarda el contenido de la evidencia ya registrada; participa de la transacción del llamador. */
    void save(long evidenceId, long reportId, int ordinal, String fileName, String contentType, String sha256,
              byte[] data);

    /** Las imágenes del reporte, en orden, sin leer su contenido. */
    List<EvidenceSummary> summaries(long reportId);

    Optional<EvidenceContent> find(long reportId, int ordinal);
}
