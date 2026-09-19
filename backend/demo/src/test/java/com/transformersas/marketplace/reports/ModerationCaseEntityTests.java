package com.transformersas.marketplace.reports;

import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModerationCaseEntityTests {

    private static ModerationCaseEntity caseIn(ReportStatus status, String assignedAgent) {
        ModerationCaseEntity entity = new ModerationCaseEntity();
        ReflectionTestUtils.setField(entity, "status", status);
        ReflectionTestUtils.setField(entity, "assignedAgentId", assignedAgent);
        ReflectionTestUtils.setField(entity, "openKey", 1);
        return entity;
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void firstAgentToWorkOnAPendingCaseTakesIt() {
        ModerationCaseEntity entity = caseIn(ReportStatus.PENDIENTE, null);

        entity.ensureWorkableBy("agent_1");

        assertThat(entity.getStatus()).isEqualTo(ReportStatus.EN_REVISION);
        assertThat(entity.getAssignedAgentId()).isEqualTo("agent_1");
    }

    @Test
    void assignedAgentCanKeepWorkingAndOthersAreRejected() {
        ModerationCaseEntity entity = caseIn(ReportStatus.EN_REVISION, "agent_1");

        entity.ensureWorkableBy("agent_1");

        assertThat(entity.getStatus()).isEqualTo(ReportStatus.EN_REVISION);
        assertConflict(() -> entity.ensureWorkableBy("agent_2"));
    }

    @Test
    void resolvedCaseAcceptsNoFurtherTransitions() {
        ModerationCaseEntity entity = caseIn(ReportStatus.RESUELTO, "agent_1");

        assertConflict(() -> entity.ensureWorkableBy("agent_1"));
        assertConflict(() -> entity.resolve(LocalDateTime.now()));
        assertConflict(entity::markInformationRequested);
    }

    @Test
    void informationCannotBeRequestedBeforeReviewStarts() {
        assertConflict(() -> caseIn(ReportStatus.PENDIENTE, null).markInformationRequested());
    }

    @Test
    void informationRequestPausesAndResumesTheReview() {
        ModerationCaseEntity entity = caseIn(ReportStatus.EN_REVISION, "agent_1");

        entity.markInformationRequested();
        assertThat(entity.getStatus()).isEqualTo(ReportStatus.INFO_SOLICITADA);

        entity.markInformationRequested();
        assertThat(entity.getStatus()).isEqualTo(ReportStatus.INFO_SOLICITADA);

        entity.resumeReview();
        assertThat(entity.getStatus()).isEqualTo(ReportStatus.EN_REVISION);
    }

    @Test
    void resolvingClosesTheCaseAndReleasesTheOpenKey() {
        ModerationCaseEntity entity = caseIn(ReportStatus.EN_REVISION, "agent_1");
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 10, 0);

        entity.resolve(now);

        assertThat(entity.isOpen()).isFalse();
        assertThat(entity.getOpenKey()).isNull();
        assertThat(entity.getResolvedAt()).isEqualTo(now);
    }
}
