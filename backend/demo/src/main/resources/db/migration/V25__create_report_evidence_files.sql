-- Imágenes de evidencia de un reporte (CU-20). Se guardan en la base junto al reporte: el listado
-- de reportes nunca lee esta tabla, solo el endpoint que sirve la imagen.
CREATE TABLE report_evidence_files (
    evidence_id BIGINT NOT NULL PRIMARY KEY,
    report_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    data LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_report_evidence_files_evidence
        FOREIGN KEY (evidence_id) REFERENCES report_evidences (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_evidence_files_report
        FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE,
    CONSTRAINT uq_report_evidence_files_ordinal UNIQUE (report_id, ordinal)
);
