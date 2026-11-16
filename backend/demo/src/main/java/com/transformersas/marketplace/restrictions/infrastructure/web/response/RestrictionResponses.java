package com.transformersas.marketplace.restrictions.infrastructure.web.response;

import com.transformersas.marketplace.restrictions.application.dto.AccountEvaluation;
import com.transformersas.marketplace.restrictions.application.dto.AccountStanding;
import com.transformersas.marketplace.restrictions.application.dto.InformationRequestView;
import com.transformersas.marketplace.restrictions.application.dto.RestrictionView;
import com.transformersas.marketplace.restrictions.application.usecase.RestrictionClosing;
import com.transformersas.marketplace.restrictions.domain.model.AccountRecord;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Respuestas JSON de CU-22. Las vistas de la propia cuenta no incluyen quién aplicó o cerró la restricción: no lo
 * necesita para conocer su situación (RNF-010, postcondición 8).
 */
public final class RestrictionResponses {

    private RestrictionResponses() {
    }

    public record Account(Long id, String email, boolean active, List<String> roles) {
        public static Account from(AccountDirectoryEntry entry) {
            return new Account(entry.id(), entry.email(), entry.active(),
                    entry.roles().stream().map(Enum::name).toList());
        }
    }

    public record Record(String type, Long referenceId, LocalDateTime decidedAt, String subject, String decision,
                         String detail, BigDecimal amount) {
        static Record from(AccountRecord record) {
            return new Record(record.type().name(), record.referenceId(), record.decidedAt(), record.subject(),
                    record.decision(), record.detail(), record.amount());
        }
    }

    /** appliedBy, closedBy y el actor de cada cambio son nulos cuando {@code withActors} es falso. */
    public record Restriction(Long id, Long accountId, String scope, String role, String reason, boolean permanent,
                              LocalDateTime startsAt, LocalDateTime endsAt, String status, Long appliedBy,
                              LocalDateTime closedAt, Long closedBy, String closeReason, List<Change> changes) {

        public static Restriction from(RestrictionView view, boolean withActors) {
            var restriction = view.restriction();
            return new Restriction(restriction.id(), restriction.accountId(), restriction.scope().name(),
                    restriction.role() == null ? null : restriction.role().name(), restriction.reason(),
                    restriction.permanent(), restriction.startsAt(), restriction.endsAt(),
                    restriction.status().name(), withActors ? restriction.appliedBy() : null,
                    restriction.closedAt(), withActors ? restriction.closedBy() : null, restriction.closeReason(),
                    view.changes().stream().map(change -> Change.from(change, withActors)).toList());
        }
    }

    public record Change(String type, LocalDateTime previousEndsAt, LocalDateTime newEndsAt, String justification,
                         Long actorId, LocalDateTime createdAt) {
        static Change from(RestrictionChange change, boolean withActor) {
            return new Change(change.type().name(), change.previousEndsAt(), change.newEndsAt(),
                    change.justification(), withActor ? change.actorId() : null, change.createdAt());
        }
    }

    public record Information(Long id, String message, LocalDateTime requestedAt, LocalDateTime dueAt,
                              String status, String response, LocalDateTime respondedAt) {
        public static Information from(InformationRequestView view) {
            var request = view.request();
            return new Information(request.id(), request.message(), request.requestedAt(), request.dueAt(),
                    view.status().name(), request.response(), request.respondedAt());
        }
    }

    public record Evaluation(Account account, List<Record> records, long recentRecords, boolean recidivist,
                             List<Restriction> activeRestrictions, List<Restriction> restrictions,
                             List<Information> informationRequests) {
        public static Evaluation from(AccountEvaluation evaluation) {
            return new Evaluation(Account.from(evaluation.account()),
                    evaluation.records().stream().map(Record::from).toList(), evaluation.recentRecords(),
                    evaluation.recidivist(),
                    evaluation.activeRestrictions().stream().map(view -> Restriction.from(view, true)).toList(),
                    evaluation.restrictions().stream().map(view -> Restriction.from(view, true)).toList(),
                    evaluation.informationRequests().stream().map(Information::from).toList());
        }
    }

    public record Standing(List<Restriction> activeRestrictions, List<Information> informationRequests) {
        public static Standing from(AccountStanding standing) {
            return new Standing(
                    standing.activeRestrictions().stream().map(view -> Restriction.from(view, false)).toList(),
                    standing.informationRequests().stream().map(Information::from).toList());
        }
    }

    public record Outcome(Long restrictionId, String status) {
        public static Outcome from(RestrictionClosing.Outcome outcome) {
            return new Outcome(outcome.restrictionId(), outcome.status().name());
        }
    }
}
