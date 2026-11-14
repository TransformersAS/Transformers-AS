package com.transformersas.marketplace.suspensions.infrastructure.web.response;

import com.transformersas.marketplace.suspensions.application.dto.ClarificationView;
import com.transformersas.marketplace.suspensions.application.dto.SellerStanding;
import com.transformersas.marketplace.suspensions.application.dto.StoreEvaluation;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionPreview;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionView;
import com.transformersas.marketplace.suspensions.application.usecase.SuspensionClosing;
import com.transformersas.marketplace.suspensions.domain.model.PendingOperations;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecord;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecordSummary;
import com.transformersas.marketplace.suspensions.domain.model.StoreReview;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Respuestas JSON de CU-27. Las vistas del vendedor no incluyen las cuentas de los administradores que actuaron:
 * no las necesita para atender su tienda (RNF-010).
 */
public final class SuspensionResponses {

    private SuspensionResponses() {
    }

    public record StoreSummary(Long storeId, String storeName, String storeStatus, long claims, long moderations,
                               long suspensions, long recentRecords, LocalDateTime lastRecordAt, boolean recidivist,
                               boolean activeSuspension, boolean openClarification) {

        public static StoreSummary from(StoreRecordSummary summary) {
            return new StoreSummary(summary.storeId(), summary.storeName(), summary.storeStatus(), summary.claims(),
                    summary.moderations(), summary.suspensions(), summary.recentRecords(), summary.lastRecordAt(),
                    summary.recidivist(), summary.activeSuspension(), summary.openClarification());
        }
    }

    public record Evaluation(Long storeId, String storeName, String storeStatus, String statusReason,
                             PendingOperations pending, List<Record> records, long recentRecords,
                             boolean recidivist, Suspension activeSuspension, List<Suspension> suspensions,
                             List<Review> reviews, List<Clarification> clarifications) {

        public static Evaluation from(StoreEvaluation evaluation) {
            return new Evaluation(evaluation.store().storeId(), evaluation.store().name(),
                    evaluation.store().status().name(), evaluation.store().statusReason(), evaluation.pending(),
                    evaluation.records().stream().map(Record::from).toList(), evaluation.recentRecords(),
                    evaluation.recidivist(),
                    evaluation.activeSuspension() == null ? null : Suspension.from(evaluation.activeSuspension(), true),
                    evaluation.suspensions().stream().map(view -> Suspension.from(view, true)).toList(),
                    evaluation.reviews().stream().map(Review::from).toList(),
                    evaluation.clarifications().stream().map(Clarification::from).toList());
        }
    }

    public record Record(String type, Long referenceId, LocalDateTime decidedAt, String subject, String decision,
                         String detail, BigDecimal amount) {

        static Record from(StoreRecord record) {
            return new Record(record.type().name(), record.referenceId(), record.decidedAt(), record.subject(),
                    record.decision(), record.detail(), record.amount());
        }
    }

    /** appliedBy, closedBy y el actor de cada cambio son nulos en las vistas del vendedor. */
    public record Suspension(Long id, Long storeId, String reason, LocalDateTime startsAt, LocalDateTime endsAt,
                             String status, Long appliedBy, LocalDateTime closedAt, Long closedBy,
                             String closeReason, List<Change> changes) {

        public static Suspension from(SuspensionView view, boolean withActors) {
            var suspension = view.suspension();
            return new Suspension(suspension.id(), suspension.storeId(), suspension.reason(), suspension.startsAt(),
                    suspension.endsAt(), suspension.status().name(), withActors ? suspension.appliedBy() : null,
                    suspension.closedAt(), withActors ? suspension.closedBy() : null, suspension.closeReason(),
                    view.changes().stream().map(change -> Change.from(change, withActors)).toList());
        }
    }

    public record Change(String type, LocalDateTime previousEndsAt, LocalDateTime newEndsAt, String justification,
                         Long actorId, boolean reactivationBlocked, LocalDateTime createdAt) {

        static Change from(SuspensionChange change, boolean withActor) {
            return new Change(change.type().name(), change.previousEndsAt(), change.newEndsAt(),
                    change.justification(), withActor ? change.actorId() : null, change.reactivationBlocked(),
                    change.createdAt());
        }
    }

    public record Review(Long id, String decision, String note, Long suspensionId, Long reviewedBy,
                         LocalDateTime createdAt) {

        public static Review from(StoreReview review) {
            return new Review(review.id(), review.decision().name(), review.note(), review.suspensionId(),
                    review.reviewedBy(), review.createdAt());
        }
    }

    public record Clarification(Long id, String message, LocalDateTime requestedAt, LocalDateTime dueAt,
                                String status, String response, LocalDateTime respondedAt) {

        public static Clarification from(ClarificationView view) {
            var request = view.request();
            return new Clarification(request.id(), request.message(), request.requestedAt(), request.dueAt(),
                    view.status().name(), request.response(), request.respondedAt());
        }
    }

    public record Preview(Long storeId, String storeName, String reason, int durationDays, LocalDateTime startsAt,
                          LocalDateTime endsAt, List<String> blocked, List<String> allowed,
                          PendingOperations pending, String warning) {

        public static Preview from(SuspensionPreview preview) {
            return new Preview(preview.storeId(), preview.storeName(), preview.reason(), preview.durationDays(),
                    preview.startsAt(), preview.endsAt(), preview.blocked(), preview.allowed(), preview.pending(),
                    preview.warning());
        }
    }

    public record Outcome(Long suspensionId, String status, boolean reactivated) {

        public static Outcome from(SuspensionClosing.Outcome outcome) {
            return new Outcome(outcome.suspensionId(), outcome.status().name(), outcome.reactivated());
        }
    }

    public record Standing(String status, String statusReason, Suspension activeSuspension,
                           List<Suspension> suspensions, List<Clarification> clarifications, List<String> blocked,
                           List<String> allowed) {

        public static Standing from(SellerStanding standing) {
            return new Standing(standing.status().name(), standing.statusReason(),
                    standing.activeSuspension() == null ? null : Suspension.from(standing.activeSuspension(), false),
                    standing.suspensions().stream().map(view -> Suspension.from(view, false)).toList(),
                    standing.clarifications().stream().map(Clarification::from).toList(), standing.blocked(),
                    standing.allowed());
        }
    }
}
