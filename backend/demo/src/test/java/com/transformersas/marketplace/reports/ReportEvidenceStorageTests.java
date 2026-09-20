package com.transformersas.marketplace.reports;

import com.transformersas.marketplace.reports.application.ReportIntakeService;
import com.transformersas.marketplace.reports.application.ReportIntakeService.EvidenceInput;
import com.transformersas.marketplace.reports.application.ReportIntakeService.Intake;
import com.transformersas.marketplace.reports.application.ReportIntakeService.SubmitReport;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tres imágenes de 5 MB guardadas en una sola transacción caben en el {@code max_allowed_packet} del MySQL del
 * proyecto (8.4, sin ajuste propio en compose.yaml ni stack.yml) y se leen enteras.
 */
class ReportEvidenceStorageTests extends ContentReportSupport {
    private static final int FIVE_MB = 5 * 1024 * 1024;

    @Autowired ReportEvidenceStorage storage;
    @Autowired ReportIntakeService intake;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void theServerAcceptsThreeFiveMegabyteImagesInOneTransaction() {
        assertThat(jdbc.queryForObject("SELECT @@max_allowed_packet", Long.class)).isGreaterThan(16L * 1024 * 1024);
        Intake created = intake.submit(new SubmitReport("42", ReportContentType.PUBLICACION, "1", "SPAM", "x",
                List.of(new EvidenceInput("u1", "image/png", (long) FIVE_MB),
                        new EvidenceInput("u2", "image/png", (long) FIVE_MB),
                        new EvidenceInput("u3", "image/png", (long) FIVE_MB))));
        List<Long> evidenceIds = jdbc.queryForList("SELECT id FROM report_evidences ORDER BY id", Long.class);
        byte[] data = new byte[FIVE_MB];
        new Random(1).nextBytes(data);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < 3; i++) {
                storage.save(evidenceIds.get(i), created.reportId(), i + 1, "e" + i + ".png", "image/png",
                        "0".repeat(64), data);
            }
        });

        assertThat(jdbc.queryForList("SELECT LENGTH(data) FROM report_evidence_files ORDER BY ordinal", Long.class))
                .containsExactly((long) FIVE_MB, (long) FIVE_MB, (long) FIVE_MB);
        assertThat(storage.summaries(created.reportId())).hasSize(3)
                .allSatisfy(summary -> assertThat(summary.sizeBytes()).isEqualTo(FIVE_MB));
        assertThat(storage.find(created.reportId(), 2).orElseThrow().data()).isEqualTo(data);
    }
}
