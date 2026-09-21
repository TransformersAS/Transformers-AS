package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportReason;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.domain.model.ReporterOutcome;
import com.transformersas.marketplace.reports.domain.model.ReporterStatus;
import com.transformersas.marketplace.reports.domain.port.ContentOwnerResolver;
import com.transformersas.marketplace.reports.domain.port.ReportableContentVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vocabulario que ve el reportante (RF-149) y registro de tipos reportables (CU-20). */
class ReporterVocabularyTests {

    @Test
    void everyInternalCaseStatusHasATranslationForTheReporter() {
        for (ReportStatus status : ReportStatus.values()) {
            assertThat(ReporterStatus.from(status)).isNotNull();
        }
        assertThat(ReporterStatus.from(ReportStatus.INFO_SOLICITADA)).isEqualTo(ReporterStatus.ESPERANDO_INFORMACION);
        assertThat(ReporterStatus.from(ReportStatus.RESUELTO)).isEqualTo(ReporterStatus.RESUELTO);
    }

    @Test
    void everyModerationDecisionHasAnOutcomeForTheReporter() {
        for (ModerationDecision decision : ModerationDecision.values()) {
            assertThat(ReporterOutcome.from(decision)).isNotNull();
        }
        assertThat(ReporterOutcome.from(ModerationDecision.MANTENER)).isEqualTo(ReporterOutcome.MANTENIDO);
        assertThat(ReporterOutcome.from(ModerationDecision.RETIRAR)).isEqualTo(ReporterOutcome.RETIRADO);
        assertThat(ReporterOutcome.from(ModerationDecision.OCULTAR_TEMPORALMENTE)).isEqualTo(ReporterOutcome.OCULTO);
    }

    @Test
    void reasonsAreLookedUpByExactCodeAndPurchaseProblemsAreFlagged() {
        assertThat(ReportReason.fromCode(" SPAM ")).contains(ReportReason.SPAM);
        assertThat(ReportReason.fromCode("spam")).isEmpty();
        assertThat(ReportReason.fromCode(null)).isEmpty();
        assertThat(List.of(ReportReason.values()).stream().filter(ReportReason::purchaseProblem).toList())
                .containsExactly(ReportReason.PRODUCTO_NO_RECIBIDO, ReportReason.PRODUCTO_DEFECTUOSO,
                        ReportReason.REEMBOLSO_O_DEVOLUCION);
    }

    @Test
    void aContentTypeBecomesReportableByRegisteringItsVerifierAndOwnerResolver() {
        ReportableContentVerifier reviews = new ReportableContentVerifier() {
            @Override
            public ReportContentType type() {
                return ReportContentType.RESENA;
            }

            @Override
            public boolean isAccessibleTo(String contentId, String reporterId) {
                return true;
            }
        };
        ContentOwnerResolver reviewOwner = new ContentOwnerResolver() {
            @Override
            public ReportContentType type() {
                return ReportContentType.RESENA;
            }

            @Override
            public Optional<String> ownerAccountId(String contentId) {
                return Optional.of("7");
            }
        };

        ReportableContentRegistry empty = new ReportableContentRegistry(List.of(), List.of());
        assertThatThrownBy(() -> empty.verifier(ReportContentType.RESENA)).isInstanceOfSatisfying(
                ReportException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CONTENT_TYPE_NOT_REPORTABLE");
                });
        assertThat(empty.ownerOf(ReportContentType.RESENA, "1")).isEmpty();

        ReportableContentRegistry registry = new ReportableContentRegistry(List.of(reviews), List.of(reviewOwner));
        assertThat(registry.verifier(ReportContentType.RESENA)).isSameAs(reviews);
        assertThat(registry.ownerOf(ReportContentType.RESENA, "1")).contains("7");
        assertThatThrownBy(() -> registry.verifier(ReportContentType.MENSAJE)).isInstanceOf(ReportException.class);
    }
}
