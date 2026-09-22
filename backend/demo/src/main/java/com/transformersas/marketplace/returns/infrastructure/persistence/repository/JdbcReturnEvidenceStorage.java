package com.transformersas.marketplace.returns.infrastructure.persistence.repository;

import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
class JdbcReturnEvidenceStorage implements ReturnEvidenceStorage {
    private final JdbcClient jdbc;

    JdbcReturnEvidenceStorage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(long returnId, int ordinal, String fileName, String contentType, String sha256, byte[] data) {
        jdbc.sql("""
                        INSERT INTO return_evidence_files (return_id, ordinal, file_name, content_type, size_bytes, sha256,
                            data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(returnId, ordinal, fileName, contentType, data.length, sha256, data, LocalDateTime.now())
                .update();
    }

    @Override
    public List<EvidenceSummary> summaries(long returnId) {
        return jdbc.sql("""
                        SELECT ordinal, file_name, content_type, size_bytes, sha256
                        FROM return_evidence_files WHERE return_id = ? ORDER BY ordinal""").param(returnId)
                .query((rs, n) -> new EvidenceSummary(rs.getInt("ordinal"), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"))).list();
    }

    @Override
    public Optional<EvidenceContent> find(long returnId, int ordinal) {
        return jdbc.sql("""
                        SELECT ordinal, file_name, content_type, size_bytes, sha256, data
                        FROM return_evidence_files WHERE return_id = ? AND ordinal = ?""").params(returnId, ordinal)
                .query((rs, n) -> new EvidenceContent(new EvidenceSummary(rs.getInt("ordinal"),
                        rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getString("sha256")), rs.getBytes("data"))).optional();
    }
}
