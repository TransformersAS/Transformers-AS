package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
class JdbcReportEvidenceStorage implements ReportEvidenceStorage {
    private final JdbcClient jdbc;

    JdbcReportEvidenceStorage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(long evidenceId, long reportId, int ordinal, String fileName, String contentType,
                     String sha256, byte[] data) {
        jdbc.sql("""
                        INSERT INTO report_evidence_files
                            (evidence_id, report_id, ordinal, file_name, content_type, size_bytes, sha256, data,
                             created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(evidenceId, reportId, ordinal, fileName, contentType, data.length, sha256, data,
                        LocalDateTime.now())
                .update();
    }

    @Override
    public List<EvidenceSummary> summaries(long reportId) {
        return jdbc.sql("""
                        SELECT ordinal, file_name, content_type, size_bytes, sha256
                        FROM report_evidence_files WHERE report_id = ? ORDER BY ordinal""")
                .params(reportId)
                .query((rs, row) -> new EvidenceSummary(rs.getInt("ordinal"), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256")))
                .list();
    }

    @Override
    public Optional<EvidenceContent> find(long reportId, int ordinal) {
        return jdbc.sql("""
                        SELECT ordinal, file_name, content_type, size_bytes, sha256, data
                        FROM report_evidence_files WHERE report_id = ? AND ordinal = ?""")
                .params(reportId, ordinal)
                .query((rs, row) -> new EvidenceContent(new EvidenceSummary(rs.getInt("ordinal"),
                        rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getString("sha256")), rs.getBytes("data")))
                .optional();
    }
}
