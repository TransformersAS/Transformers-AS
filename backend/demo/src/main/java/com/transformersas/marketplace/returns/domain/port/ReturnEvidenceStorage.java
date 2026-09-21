package com.transformersas.marketplace.returns.domain.port;

import java.util.List;
import java.util.Optional;

/** Almacén de las imágenes de evidencia de una devolución (RF-050). */
public interface ReturnEvidenceStorage {

    /** Datos de una imagen sin su contenido; {@code ordinal} empieza en 1 dentro de la devolución. */
    record EvidenceSummary(int ordinal, String fileName, String contentType, long sizeBytes, String sha256) {
    }

    /** Imagen completa, para servirla. */
    record EvidenceContent(EvidenceSummary summary, byte[] data) {
    }

    /** Guarda una imagen; participa de la transacción del llamador. */
    void save(long returnId, int ordinal, String fileName, String contentType, String sha256, byte[] data);

    /** Las imágenes de la devolución, en orden, sin leer su contenido. */
    List<EvidenceSummary> summaries(long returnId);

    Optional<EvidenceContent> find(long returnId, int ordinal);
}
