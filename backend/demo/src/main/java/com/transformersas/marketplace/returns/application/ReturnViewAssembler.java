package com.transformersas.marketplace.returns.application;

import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.port.PickupStatusReader;
import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Arma lo que se muestra de una devolución. Las marcas de atraso y de plazo vencido se calculan aquí, al consultar, con
 * el reloj y la política: no hay tarea periódica que las mantenga (D2, RNF-047).
 */
@Component
public class ReturnViewAssembler {
    private final ReturnRequestRepository repository;
    private final ReturnEvidenceStorage evidence;
    private final PickupStatusReader pickup;
    private final ReturnPolicy policy;
    private final Clock clock;

    ReturnViewAssembler(ReturnRequestRepository repository, ReturnEvidenceStorage evidence, PickupStatusReader pickup,
                        ReturnPolicy policy, Clock clock) {
        this.repository = repository;
        this.evidence = evidence;
        this.pickup = pickup;
        this.policy = policy;
        this.clock = clock;
    }

    public ReturnViews.Summary summary(ReturnRequest r) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new ReturnViews.Summary(r.getId(), r.getOrderId(), r.getLine().orderItemId(),
                r.getLine().productName(), r.getLine().quantity(), r.getRefundAmount(), r.getStatus(),
                r.getReason().label(), r.getOrigin(), r.getCreatedAt(), r.getUpdatedAt(),
                r.isSellerDecisionOverdue(now, policy), r.isMethodSelectionOverdue(now, policy), awaiting(r, now),
                pickupBlocked(r));
    }

    public ReturnViews.Detail detail(ReturnRequest r) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReturnViews.InformationView> information = repository.informationHistory(r.getId()).stream()
                .map(record -> new ReturnViews.InformationView(record.id(), record.message(), record.requestedAt(),
                        record.dueAt(), record.status(), "OPEN".equals(record.status()) && now.isAfter(record.dueAt()),
                        record.responseText(), record.respondedAt())).toList();
        List<ReturnViews.EvidenceView> images = evidence.summaries(r.getId()).stream()
                .map(image -> new ReturnViews.EvidenceView(image.ordinal(), image.fileName(), image.contentType(),
                        image.sizeBytes())).toList();
        List<ReturnViews.TimelineView> timeline = repository.timeline(r.getId()).stream()
                .map(entry -> new ReturnViews.TimelineView(entry.type(), entry.from(), entry.to(),
                        entry.actorType().name(), entry.details(), entry.createdAt())).toList();
        ReturnViews.Decision decision = r.getDecision() == null ? null
                : new ReturnViews.Decision(r.getDecision().note(), r.getDecision().decidedAt());
        ReturnRequest.ProblemReport problem = r.getProblem();
        return new ReturnViews.Detail(r.getId(), r.getOrderId(), r.getLine().orderItemId(), r.getLine().productId(),
                r.getLine().productName(), r.getLine().quantity(), r.getLine().unitPrice(), r.getRefundAmount(),
                r.getStatus(), r.getReason().name(), r.getReason().label(), r.getDescription(), r.getOrigin(),
                r.getOriginClaimId(), r.getCreatedAt(), r.getUpdatedAt(), r.returnWindowEndsAt().orElse(null),
                decision, r.getReturnMethodCode(), r.getInspectionDueAt(), problem != null,
                problem == null ? null : problem.description(), problem == null ? null : problem.claimId(),
                pickupBlocked(r), r.isSellerDecisionOverdue(now, policy), r.isMethodSelectionOverdue(now, policy),
                awaiting(r, now), information, images, timeline);
    }

    private static boolean awaiting(ReturnRequest r, LocalDateTime now) {
        return r.getStatus() == ReturnStatus.INFO_REQUIRED && r.getOpenInformation() != null
                && !r.getOpenInformation().isExpired(now);
    }

    private boolean pickupBlocked(ReturnRequest r) {
        return r.getStatus() == ReturnStatus.APPROVED && pickup.pickupBlocked(r.getId());
    }
}
