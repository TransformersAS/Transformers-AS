package com.transformersas.marketplace.returns.domain.model;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La máquina de estados de una devolución (CU-19): transiciones válidas, plazos y plazos derivados. */
class ReturnRequestTests {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final ReturnPolicy POLICY = ReturnPolicy.DEFAULT;
    private static final long BUYER = 7L;
    private static final long SELLER = 9L;

    private static ReturnLine line() {
        return new ReturnLine(10L, 5L, "Lámpara", 2, new BigDecimal("10.00"));
    }

    private static ReturnRequest requested() {
        ReturnRequest request = ReturnRequest.request(100L, line(), BUYER, 1L, ReturnReason.DEFECTIVE,
                "  No enciende  ", 30, T0.minusDays(3), T0);
        request.assignId(55L);
        return request;
    }

    private static ReturnRequest inReview() {
        ReturnRequest request = requested();
        request.startReview(SELLER, T0.plusHours(1));
        return request;
    }

    private static ReturnRequest approved() {
        ReturnRequest request = inReview();
        request.approve(SELLER, "Se acepta", T0.plusHours(2));
        return request;
    }

    private static ReturnRequest inInspection() {
        ReturnRequest request = approved();
        request.chooseReturnMethod(BUYER, "PICKUP", T0.plusHours(3));
        request.startInspection(T0.plusDays(3), POLICY.inspectionWindow());
        return request;
    }

    private static ReturnRequest refundPending() {
        ReturnRequest request = inInspection();
        request.beginRefund(T0.plusDays(4));
        return request;
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private static void assertInvalid(Runnable action, String code, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.kind()).isEqualTo(BusinessException.Kind.INVALID);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.details()).isEqualTo(Map.of("field", field));
        });
    }

    // ---------- Creación ----------

    @Test
    void aBuyerRequestStartsRequestedWithACopyOfTheLineTheWindowAndTheDeliveryDate() {
        ReturnRequest request = requested();

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(request.getOrigin()).isEqualTo(ReturnOrigin.BUYER);
        assertThat(request.getDescription()).isEqualTo("No enciende");
        assertThat(request.getLine().refundAmount()).isEqualByComparingTo("20.00");
        assertThat(request.getReturnWindowDays()).isEqualTo(30);
        assertThat(request.returnWindowEndsAt()).contains(T0.minusDays(3).plusDays(30));
        assertThat(request.openingEvent()).isEqualTo(new ReturnEvent(ReturnEventType.REQUESTED, null,
                ReturnStatus.REQUESTED, ActorType.BUYER, BUYER, null));
        assertThat(request.refundKey()).isEqualTo("return-55");
    }

    @Test
    void incompleteDataIsRejectedNamingTheField() {
        assertInvalid(() -> ReturnRequest.request(100L, line(), BUYER, 1L, null, "x", 30, T0, T0),
                "RETURN_REASON_REQUIRED", "reason");
        assertInvalid(() -> ReturnRequest.request(100L, line(), BUYER, 1L, ReturnReason.OTHER, "   ", 30, T0, T0),
                "RETURN_DESCRIPTION_REQUIRED", "description");
        assertInvalid(() -> ReturnRequest.request(100L, line(), BUYER, 1L, ReturnReason.OTHER, null, 30, T0, T0),
                "RETURN_DESCRIPTION_REQUIRED", "description");
        assertInvalid(() -> ReturnRequest.request(100L, line(), BUYER, 1L, ReturnReason.OTHER, "x".repeat(1001), 30,
                T0, T0), "RETURN_TEXT_TOO_LONG", "description");
        assertInvalid(() -> ReturnRequest.request(100L, line(), BUYER, 1L, ReturnReason.OTHER, "mal\u0000texto", 30,
                T0, T0), "RETURN_TEXT_INVALID", "description");
    }

    @Test
    void aReturnBornFromAClaimStartsApprovedWithoutWindowAndReferencesTheClaim() {
        ReturnRequest request = ReturnRequest.approvedFromClaim(100L, line(), BUYER, 1L, 44L, "Devolver el producto",
                T0);

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(request.getOrigin()).isEqualTo(ReturnOrigin.CLAIM);
        assertThat(request.getOriginClaimId()).isEqualTo(44L);
        assertThat(request.getReturnWindowDays()).isNull();
        assertThat(request.returnWindowEndsAt()).isEmpty();
        assertThat(request.getDecision().note()).contains("44");
        assertThat(request.openingEvent().type()).isEqualTo(ReturnEventType.APPROVED_FROM_CLAIM);
        assertThatThrownBy(() -> ReturnRequest.approvedFromClaim(100L, line(), BUYER, 1L, null, "x", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIdIsAssignedOnlyOnce() {
        ReturnRequest request = requested();

        assertThatThrownBy(() -> request.assignId(99L)).isInstanceOf(IllegalStateException.class);
    }

    // ---------- Camino completo ----------

    @Test
    void theHappyPathGoesFromRequestedToFinished() {
        ReturnRequest request = requested();

        ReturnEvent review = request.startReview(SELLER, T0.plusHours(1));
        assertThat(review).extracting(ReturnEvent::from, ReturnEvent::to, ReturnEvent::actorType)
                .containsExactly(ReturnStatus.REQUESTED, ReturnStatus.IN_REVIEW, ActorType.SELLER);
        ReturnEvent approve = request.approve(SELLER, "Cumple las condiciones", T0.plusHours(2));
        assertThat(approve.to()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(request.getDecision()).isEqualTo(new ReturnRequest.Decision("Cumple las condiciones", SELLER,
                T0.plusHours(2)));
        ReturnEvent method = request.chooseReturnMethod(BUYER, " PICKUP ", T0.plusHours(3));
        assertThat(method.type()).isEqualTo(ReturnEventType.METHOD_CHOSEN);
        assertThat(method.to()).isNull();
        assertThat(request.getReturnMethodCode()).isEqualTo("PICKUP");

        LocalDateTime delivered = T0.plusDays(3);
        ReturnEvent inspection = request.startInspection(delivered, POLICY.inspectionWindow()).orElseThrow();
        assertThat(inspection.to()).isEqualTo(ReturnStatus.IN_INSPECTION);
        assertThat(request.getInspectionDueAt()).isEqualTo(delivered.plusHours(24));
        assertThat(request.getNextActionAt()).isEqualTo(delivered.plusHours(24));

        ReturnEvent refund = request.beginRefund(delivered.plusHours(24));
        assertThat(refund.to()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(request.getNextActionAt()).isEqualTo(delivered.plusHours(24));
        ReturnEvent finished = request.refundCompleted(delivered.plusHours(25));
        assertThat(finished.to()).isEqualTo(ReturnStatus.FINISHED);
        assertThat(request.getStatus().isTerminal()).isTrue();
        assertThat(request.getNextActionAt()).isNull();
    }

    @Test
    void transitionsOutOfOrderAreConflictsAndDoNotChangeTheRequest() {
        ReturnRequest request = requested();

        assertConflict(() -> request.approve(SELLER, null, T0), "RETURN_INVALID_STATE");
        assertConflict(() -> request.requestInformation(SELLER, "dato", T0, POLICY.informationWindow()),
                "RETURN_INVALID_STATE");
        assertConflict(() -> request.chooseReturnMethod(BUYER, "PICKUP", T0), "RETURN_INVALID_STATE");
        assertConflict(() -> request.beginRefund(T0), "RETURN_INVALID_STATE");
        assertConflict(() -> request.refundCompleted(T0), "RETURN_INVALID_STATE");
        assertConflict(() -> request.reportProblem(SELLER, "roto", null, T0), "RETURN_INVALID_STATE");
        request.startReview(SELLER, T0);
        assertConflict(() -> request.startReview(SELLER, T0), "RETURN_INVALID_STATE");
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.IN_REVIEW);
    }

    // ---------- Información adicional (RF-052, A5) ----------

    @Test
    void theSellerAsksForInformationAndTheBuyerHas24HoursToAnswer() {
        ReturnRequest request = inReview();

        ReturnRequest.InformationAsked asked = request.requestInformation(SELLER, "  Envía una foto  ", T0.plusHours(2),
                POLICY.informationWindow());

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.INFO_REQUIRED);
        assertThat(asked.request().message()).isEqualTo("Envía una foto");
        assertThat(asked.request().dueAt()).isEqualTo(T0.plusHours(26));
        assertThat(request.getOpenInformation()).isEqualTo(asked.request());
        assertThat(asked.event().type()).isEqualTo(ReturnEventType.INFORMATION_REQUESTED);

        ReturnRequest.InformationAnswered answered = request.answerInformation(BUYER, "Aquí está", T0.plusHours(10));
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.IN_REVIEW);
        assertThat(request.getOpenInformation()).isNull();
        assertThat(answered.text()).isEqualTo("Aquí está");
        assertThat(answered.request()).isEqualTo(asked.request());
    }

    @Test
    void theInformationRequestNeedsAMessageAndTheAnswerNeedsText() {
        ReturnRequest request = inReview();

        assertInvalid(() -> request.requestInformation(SELLER, " ", T0, POLICY.informationWindow()),
                "RETURN_MESSAGE_REQUIRED", "message");
        request.requestInformation(SELLER, "dato", T0, POLICY.informationWindow());
        assertInvalid(() -> request.answerInformation(BUYER, "", T0.plusHours(1)), "RETURN_RESPONSE_REQUIRED", "text");
    }

    @Test
    void anAnswerAfterThe24HoursIsRefusedAndTheSellerThenMayReject() {
        ReturnRequest request = inReview();
        request.requestInformation(SELLER, "dato", T0, POLICY.informationWindow());

        assertThat(request.isInformationExpired(T0.plusHours(24))).isFalse();
        assertThat(request.isInformationExpired(T0.plusHours(24).plusNanos(1))).isTrue();
        assertConflict(() -> request.answerInformation(BUYER, "tarde", T0.plusHours(25)), "RETURN_INFORMATION_EXPIRED");
        assertConflict(() -> request.reject(SELLER, "No respondió", T0.plusHours(23)), "RETURN_INFORMATION_PENDING");

        ReturnEvent rejected = request.reject(SELLER, "No respondió a tiempo", T0.plusHours(25));
        assertThat(rejected.from()).isEqualTo(ReturnStatus.INFO_REQUIRED);
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(request.getOpenInformation()).isNull();
    }

    // ---------- Rechazo (RF-108, A6) ----------

    @Test
    void rejectingNeedsAJustificationAndIsTerminal() {
        ReturnRequest fromRequested = requested();
        assertInvalid(() -> fromRequested.reject(SELLER, " ", T0), "RETURN_JUSTIFICATION_REQUIRED", "note");

        ReturnEvent event = fromRequested.reject(SELLER, "El producto fue usado", T0.plusHours(1));

        assertThat(event.details()).isEqualTo("El producto fue usado");
        assertThat(fromRequested.getDecision().decidedByAccountId()).isEqualTo(SELLER);
        assertThat(fromRequested.getStatus().isTerminal()).isTrue();
        assertConflict(() -> fromRequested.startReview(SELLER, T0), "RETURN_INVALID_STATE");
        assertConflict(() -> fromRequested.reject(SELLER, "otra vez", T0), "RETURN_INVALID_STATE");
        ReturnRequest fromReview = inReview();
        fromReview.reject(SELLER, "No cumple", T0.plusHours(2));
        assertThat(fromReview.getStatus()).isEqualTo(ReturnStatus.REJECTED);
    }

    @Test
    void approvingDoesNotNeedAJustification() {
        ReturnRequest request = inReview();

        request.approve(SELLER, "   ", T0.plusHours(2));

        assertThat(request.getDecision().note()).isNull();
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    // ---------- Método de retorno (RF-109, A7) ----------

    @Test
    void theBuyerCanChangeTheReturnMethodWhileApprovedButNotLeaveItEmpty() {
        ReturnRequest request = approved();

        assertInvalid(() -> request.chooseReturnMethod(BUYER, " ", T0), "RETURN_METHOD_REQUIRED", "method");
        request.chooseReturnMethod(BUYER, "PICKUP", T0.plusHours(3));
        request.chooseReturnMethod(BUYER, "DROP_OFF", T0.plusHours(4));

        assertThat(request.getReturnMethodCode()).isEqualTo("DROP_OFF");
        assertThat(request.getMethodChosenAt()).isEqualTo(T0.plusHours(4));
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    // ---------- Inspección (A8, A10) ----------

    @Test
    void aRepeatedOrLateDeliveryEventDoesNotChangeAnything() {
        ReturnRequest request = inInspection();
        LocalDateTime due = request.getInspectionDueAt();

        assertThat(request.startInspection(T0.plusDays(9), POLICY.inspectionWindow())).isEmpty();
        assertThat(request.getInspectionDueAt()).isEqualTo(due);
        request.beginRefund(due);
        assertThat(request.startInspection(T0.plusDays(9), POLICY.inspectionWindow())).isEmpty();
        request.refundCompleted(due.plusHours(1));
        assertThat(request.startInspection(T0.plusDays(9), POLICY.inspectionWindow())).isEmpty();
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.FINISHED);
    }

    @Test
    void goodsCannotArriveForARequestThatWasNotApproved() {
        assertConflict(() -> requested().startInspection(T0, POLICY.inspectionWindow()), "RETURN_INVALID_STATE");
        ReturnRequest rejected = requested();
        rejected.reject(SELLER, "No cumple", T0);
        assertConflict(() -> rejected.startInspection(T0, POLICY.inspectionWindow()), "RETURN_INVALID_STATE");
    }

    @Test
    void aReportedProblemKeepsTheStateStopsTheAutomaticRefundAndCannotBeRepeated() {
        ReturnRequest request = inInspection();
        LocalDateTime inside = request.getInspectionStartedAt().plusHours(5);

        ReturnEvent event = request.reportProblem(SELLER, "  Llegó roto  ", 88L, inside);

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.IN_INSPECTION);
        assertThat(event.type()).isEqualTo(ReturnEventType.PROBLEM_REPORTED);
        assertThat(event.to()).isNull();
        assertThat(request.hasProblemReported()).isTrue();
        assertThat(request.getProblem()).isEqualTo(new ReturnRequest.ProblemReport("Llegó roto", inside, 88L));
        assertThat(request.getNextActionAt()).isNull();
        assertConflict(() -> request.reportProblem(SELLER, "otra", 89L, inside), "RETURN_PROBLEM_ALREADY_REPORTED");
        assertConflict(() -> request.beginRefund(request.getInspectionDueAt().plusHours(1)), "RETURN_PROBLEM_REPORTED");
    }

    @Test
    void theProblemMustBeReportedInsideTheWindowAndDescribed() {
        ReturnRequest request = inInspection();

        assertInvalid(() -> request.reportProblem(SELLER, " ", null, request.getInspectionStartedAt()),
                "RETURN_PROBLEM_REQUIRED", "description");
        assertConflict(() -> request.reportProblem(SELLER, "tarde", 1L, request.getInspectionDueAt().plusSeconds(1)),
                "RETURN_INSPECTION_CLOSED");
        assertThat(request.hasProblemReported()).isFalse();
    }

    @Test
    void theRefundStartsOnlyWhenTheInspectionWindowEnds() {
        ReturnRequest request = inInspection();

        assertConflict(() -> request.beginRefund(request.getInspectionDueAt().minusSeconds(1)),
                "RETURN_INSPECTION_OPEN");
        request.beginRefund(request.getInspectionDueAt());
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
    }

    // ---------- Reembolso (A9) ----------

    @Test
    void anIncompleteRefundIsRetriedWithTheSameKeyUntilTheMaximum() {
        ReturnPolicy policy = new ReturnPolicy(Duration.ofHours(72), Duration.ofHours(24), Duration.ofHours(24),
                Duration.ofHours(72), 3, Duration.ofMinutes(15));
        ReturnRequest request = refundPending();
        LocalDateTime now = T0.plusDays(5);

        ReturnEvent first = request.refundNotCompleted(now, "pasarela caída", policy);
        assertThat(first.type()).isEqualTo(ReturnEventType.REFUND_RETRY_SCHEDULED);
        assertThat(request.getRefundAttempts()).isEqualTo(1);
        assertThat(request.getNextActionAt()).isEqualTo(now.plusMinutes(15));
        request.refundNotCompleted(now.plusMinutes(15), "sigue pendiente", policy);
        ReturnEvent last = request.refundNotCompleted(now.plusMinutes(30), "x".repeat(600), policy);

        assertThat(last.type()).isEqualTo(ReturnEventType.REFUND_GAVE_UP);
        assertThat(last.details()).hasSize(400);
        assertThat(request.getNextActionAt()).isNull();
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(request.refundKey()).isEqualTo("return-55");
    }

    @Test
    void aRefundThatCompletesAfterRetriesFinishesTheReturn() {
        ReturnRequest request = refundPending();
        request.refundNotCompleted(T0.plusDays(5), null, POLICY);

        request.refundCompleted(T0.plusDays(5).plusMinutes(16));

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.FINISHED);
        assertConflict(() -> request.refundNotCompleted(T0, "x", POLICY), "RETURN_INVALID_STATE");
    }

    // ---------- Marcas derivadas (D2) ----------

    @Test
    void theSellerDecisionIsOverdueAfterTheConfiguredTimeButOnlyWhileTheSellerHasToAct() {
        ReturnRequest request = requested();

        assertThat(request.isSellerDecisionOverdue(T0.plusHours(72), POLICY)).isFalse();
        assertThat(request.isSellerDecisionOverdue(T0.plusHours(72).plusNanos(1), POLICY)).isTrue();
        request.startReview(SELLER, T0.plusHours(80));
        assertThat(request.isSellerDecisionOverdue(T0.plusHours(90), POLICY)).isTrue();
        request.requestInformation(SELLER, "dato", T0.plusHours(90), POLICY.informationWindow());
        assertThat(request.isSellerDecisionOverdue(T0.plusHours(100), POLICY)).isFalse();
    }

    @Test
    void chooseTheReturnMethodIsOverdueOnlyWhileApprovedWithoutOne() {
        ReturnRequest request = approved();
        LocalDateTime approvedAt = request.getDecision().decidedAt();

        assertThat(request.isMethodSelectionOverdue(approvedAt.plusHours(72), POLICY)).isFalse();
        assertThat(request.isMethodSelectionOverdue(approvedAt.plusHours(73), POLICY)).isTrue();
        request.chooseReturnMethod(BUYER, "PICKUP", approvedAt.plusHours(74));
        assertThat(request.isMethodSelectionOverdue(approvedAt.plusHours(100), POLICY)).isFalse();
    }

    @Test
    void thePolicyRejectsNonPositiveDurationsAndAttempts() {
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofHours(1), 1, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofHours(1), 0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ReturnPolicy.DEFAULT.sellerDecisionDeadline()).isEqualTo(Duration.ofHours(72));
        assertThat(ReturnPolicy.DEFAULT.informationWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(ReturnPolicy.DEFAULT.inspectionWindow()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void reasonsAreFoundByExactCode() {
        assertThat(ReturnReason.fromCode(" DEFECTIVE ")).contains(ReturnReason.DEFECTIVE);
        assertThat(ReturnReason.fromCode("defective")).isEmpty();
        assertThat(ReturnReason.fromCode(null)).isEmpty();
        assertThat(ReturnReason.OTHER.label()).isNotBlank();
    }
}
