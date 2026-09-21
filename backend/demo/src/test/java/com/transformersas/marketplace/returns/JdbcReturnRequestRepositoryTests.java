package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.returns.domain.model.InformationRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnEventType;
import com.transformersas.marketplace.returns.domain.model.ReturnOrigin;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import com.transformersas.marketplace.returns.domain.model.ReturnReason;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository.Insertion;
import com.transformersas.marketplace.shared.audit.ActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El adaptador JDBC de devoluciones: ida y vuelta de todos los campos, unicidad por línea, cola del barrido y bloqueos. */
class JdbcReturnRequestRepositoryTests extends ReturnsTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);

    @Autowired ReturnRequestRepository repository;
    @Autowired ReturnEvidenceStorage evidence;
    @Autowired TransactionTemplate tx;

    private long buyerId;
    private long sellerId;

    @BeforeEach
    void seed() {
        buyerId = createAccount("comprador@example.com", "COMPRADOR");
        sellerId = createAccount("vendedor@example.com", "VENDEDOR");
    }

    private ReturnRequest requestFor(DeliveredOrder order) {
        return ReturnRequest.request(order.orderId(), lineOf(order), buyerId, 1L, ReturnReason.DEFECTIVE, "No enciende",
                30, NOW.minusDays(3), NOW);
    }

    private ReturnRequest stored(DeliveredOrder order) {
        return repository.insertIfAbsent(requestFor(order)).request();
    }

    @Test
    void aRequestSurvivesTheRoundTripWithEveryField() {
        DeliveredOrder order = deliveredOrder(buyerId, NOW.minusDays(3));

        Insertion inserted = repository.insertIfAbsent(requestFor(order));
        ReturnRequest read = repository.findById(inserted.request().getId()).orElseThrow();

        assertThat(inserted.created()).isTrue();
        assertThat(read).usingRecursiveComparison().isEqualTo(inserted.request());
        assertThat(read.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(read.getLine().orderItemId()).isEqualTo(order.itemId());
        assertThat(read.getRefundAmount()).isEqualByComparingTo("20.00");
        assertThat(read.getReturnWindowDays()).isEqualTo(30);
        assertThat(read.getDeliveredAt()).isEqualTo(NOW.minusDays(3));
    }

    @Test
    void aSecondRequestForTheSameLineReturnsTheExistingOneEvenWhenItWasRejected() {
        DeliveredOrder order = deliveredOrder(buyerId, NOW.minusDays(3));
        ReturnRequest first = stored(order);
        first.reject(sellerId, "No cumple", NOW.plusHours(1));
        tx.executeWithoutResult(s -> {
            repository.lockById(first.getId());
            repository.save(first);
        });

        Insertion again = repository.insertIfAbsent(requestFor(order));

        assertThat(again.created()).isFalse();
        assertThat(again.request().getId()).isEqualTo(first.getId());
        assertThat(again.request().getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(count("return_requests")).isEqualTo(1);
    }

    @Test
    void everyTransitionIsSavedAndReadBackIncludingTheOpenInformationRequest() {
        DeliveredOrder order = deliveredOrder(buyerId, NOW.minusDays(3));
        ReturnRequest request = stored(order);
        Long id = request.getId();

        tx.executeWithoutResult(s -> {
            ReturnRequest locked = repository.lockById(id).orElseThrow();
            locked.startReview(sellerId, NOW.plusHours(1));
            ReturnRequest.InformationAsked asked = locked.requestInformation(sellerId, "Envía una foto",
                    NOW.plusHours(2), ReturnPolicy.DEFAULT.informationWindow());
            repository.addInformationRequest(id, asked.request());
            repository.save(locked);
        });
        ReturnRequest waiting = repository.findById(id).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReturnStatus.INFO_REQUIRED);
        assertThat(waiting.getOpenInformation().message()).isEqualTo("Envía una foto");
        assertThat(waiting.getOpenInformation().dueAt()).isEqualTo(NOW.plusHours(26));

        tx.executeWithoutResult(s -> {
            ReturnRequest locked = repository.lockById(id).orElseThrow();
            ReturnRequest.InformationAnswered answered = locked.answerInformation(buyerId, "Adjunta", NOW.plusHours(5));
            repository.answerInformationRequest(answered.request().id(), answered.text(), answered.respondedAt());
            locked.approve(sellerId, "Se acepta", NOW.plusHours(6));
            locked.chooseReturnMethod(buyerId, "PICKUP", NOW.plusHours(7));
            locked.startInspection(NOW.plusDays(2), ReturnPolicy.DEFAULT.inspectionWindow());
            repository.save(locked);
        });
        ReturnRequest inspecting = repository.findById(id).orElseThrow();

        assertThat(inspecting.getOpenInformation()).isNull();
        assertThat(inspecting.getStatus()).isEqualTo(ReturnStatus.IN_INSPECTION);
        assertThat(inspecting.getDecision().decidedByAccountId()).isEqualTo(sellerId);
        assertThat(inspecting.getDecision().note()).isEqualTo("Se acepta");
        assertThat(inspecting.getReturnMethodCode()).isEqualTo("PICKUP");
        assertThat(inspecting.getInspectionDueAt()).isEqualTo(NOW.plusDays(2).plusHours(24));
        assertThat(inspecting.getNextActionAt()).isEqualTo(inspecting.getInspectionDueAt());
        assertThat(repository.informationHistory(id)).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo("ANSWERED");
            assertThat(record.responseText()).isEqualTo("Adjunta");
            assertThat(record.respondedAt()).isEqualTo(NOW.plusHours(5));
        });
    }

    @Test
    void aReportedProblemAndTheRefundAttemptsAreSaved() {
        DeliveredOrder order = deliveredOrder(buyerId, NOW.minusDays(3));
        ReturnRequest request = stored(order);
        request.startReview(sellerId, NOW);
        request.approve(sellerId, null, NOW);
        request.startInspection(NOW, ReturnPolicy.DEFAULT.inspectionWindow());
        request.reportProblem(sellerId, "Llegó roto", 88L, NOW.plusHours(2));
        tx.executeWithoutResult(s -> {
            repository.lockById(request.getId());
            repository.save(request);
        });

        ReturnRequest read = repository.findById(request.getId()).orElseThrow();

        assertThat(read.hasProblemReported()).isTrue();
        assertThat(read.getProblem()).isEqualTo(new ReturnRequest.ProblemReport("Llegó roto", NOW.plusHours(2), 88L));
        assertThat(read.getNextActionAt()).isNull();
        assertThat(read.getDecision().note()).isNull();
    }

    @Test
    void aClaimBornReturnKeepsItsOriginAndAReopenedOneChangesIt() {
        DeliveredOrder born = deliveredOrder(buyerId, NOW.minusDays(3));
        ReturnRequest fromClaim = ReturnRequest.approvedFromClaim(born.orderId(), lineOf(born), buyerId, 1L, 44L,
                "Devolver", new java.math.BigDecimal("12.50"), NOW);
        repository.insertIfAbsent(fromClaim);

        ReturnRequest read = repository.findById(fromClaim.getId()).orElseThrow();
        assertThat(read.getOrigin()).isEqualTo(ReturnOrigin.CLAIM);
        assertThat(read.getOriginClaimId()).isEqualTo(44L);
        assertThat(read.getReturnWindowDays()).isNull();
        assertThat(read.getRefundAmount()).isEqualByComparingTo("12.50");

        DeliveredOrder rejectedOrder = deliveredOrder(buyerId, NOW.minusDays(3));
        ReturnRequest rejected = stored(rejectedOrder);
        rejected.reject(sellerId, "No cumple", NOW);
        rejected.reopenFromClaim(45L, null, NOW.plusDays(1));
        tx.executeWithoutResult(s -> {
            repository.lockByOrderItemId(rejectedOrder.itemId());
            repository.save(rejected);
        });
        ReturnRequest reopened = repository.findByOrderItemId(rejectedOrder.itemId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(reopened.getOrigin()).isEqualTo(ReturnOrigin.CLAIM);
        assertThat(reopened.getOriginClaimId()).isEqualTo(45L);
        assertThat(reopened.getReturnWindowDays()).isEqualTo(30);
    }

    @Test
    void listsAreScopedToTheBuyerAndTheStoreAndFilteredByStatus() {
        long other = createAccount("otra@example.com", "COMPRADOR");
        ReturnRequest first = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        ReturnRequest second = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        second.startReview(sellerId, NOW);
        tx.executeWithoutResult(s -> {
            repository.lockById(second.getId());
            repository.save(second);
        });
        DeliveredOrder foreign = deliveredOrder(other, NOW.minusDays(3));
        repository.insertIfAbsent(ReturnRequest.request(foreign.orderId(), lineOf(foreign), other, 1L,
                ReturnReason.OTHER, "x", 30, NOW.minusDays(3), NOW));

        assertThat(repository.findByBuyer(buyerId)).extracting(ReturnRequest::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(repository.findByStore(1L, null)).hasSize(3);
        assertThat(repository.findByStore(1L, ReturnStatus.IN_REVIEW)).extracting(ReturnRequest::getId)
                .containsExactly(second.getId());
        assertThat(repository.findByStore(2L, null)).isEmpty();
        assertThat(repository.findByOrderItemIds(List.of(first.getLine().orderItemId(),
                second.getLine().orderItemId()))).hasSize(2);
        assertThat(repository.findByOrderItemIds(List.of())).isEmpty();
        assertThat(repository.findById(987654L)).isEmpty();
    }

    @Test
    void theTimelineKeepsOrderAndTruncatesLongDetails() {
        ReturnRequest request = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        repository.appendEvent(request.getId(), request.openingEvent(), "corr-1", NOW);
        repository.appendEvent(request.getId(), new ReturnEvent(ReturnEventType.REJECTED, ReturnStatus.REQUESTED,
                ReturnStatus.REJECTED, ActorType.SELLER, sellerId, "x".repeat(700)), "corr-2", NOW.plusHours(1));

        List<ReturnRequestRepository.TimelineEntry> timeline = repository.timeline(request.getId());

        assertThat(timeline).extracting(ReturnRequestRepository.TimelineEntry::type)
                .containsExactly(ReturnEventType.REQUESTED, ReturnEventType.REJECTED);
        assertThat(timeline.get(0).from()).isNull();
        assertThat(timeline.get(0).to()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(timeline.get(0).actorType()).isEqualTo(ActorType.BUYER);
        assertThat(timeline.get(0).actorId()).isEqualTo(buyerId);
        assertThat(timeline.get(1).details()).hasSize(500);
        assertThat(timeline.get(1).createdAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void theSweepQueueHoldsOnlyDueInspectionsAndRefundsOldestFirstAndInBatches() {
        long a = dueReturn(NOW.minusHours(3), ReturnStatus.IN_INSPECTION);
        long b = dueReturn(NOW.minusHours(5), ReturnStatus.REFUND_PENDING);
        long notDue = dueReturn(NOW.plusHours(1), ReturnStatus.IN_INSPECTION);
        long noAction = dueReturn(null, ReturnStatus.IN_INSPECTION);
        long approved = dueReturn(NOW.minusHours(9), ReturnStatus.APPROVED);

        List<Long> all = tx.execute(s -> repository.lockDueIds(NOW, 10));
        List<Long> limited = tx.execute(s -> repository.lockDueIds(NOW, 1));

        assertThat(all).containsExactly(b, a).doesNotContain(notDue, noAction, approved);
        assertThat(limited).containsExactly(b);
    }

    @Test
    void aReturnLockedByOneTransactionIsSkippedNotWaitedForByTheSweepOfAnother() throws Exception {
        long first = dueReturn(NOW.minusHours(5), ReturnStatus.IN_INSPECTION);
        long second = dueReturn(NOW.minusHours(4), ReturnStatus.IN_INSPECTION);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<List<Long>> holder = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            List<Long> mine = repository.lockDueIds(NOW, 1);
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mine;
        }));
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        List<Long> other = tx.execute(s -> repository.lockDueIds(NOW, 10));
        release.countDown();

        assertThat(holder.get(20, TimeUnit.SECONDS)).containsExactly(first);
        assertThat(other).containsExactly(second);
    }

    private long dueReturn(LocalDateTime nextAction, ReturnStatus status) {
        ReturnRequest request = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        jdbc.update("UPDATE return_requests SET status = ?, next_action_at = ? WHERE id = ?", status.name(), nextAction,
                request.getId());
        return request.getId();
    }

    @Test
    void anInformationRequestCannotBeOpenedTwiceAndAnsweringItTwiceChangesNothing() {
        ReturnRequest request = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        InformationRequest info = new InformationRequest(null, "dato", sellerId, NOW, NOW.plusHours(24));
        Long infoId = repository.addInformationRequest(request.getId(), info);

        assertThatThrownBy(() -> repository.addInformationRequest(request.getId(), info))
                .isInstanceOf(DuplicateKeyException.class);
        repository.answerInformationRequest(infoId, "primera", NOW.plusHours(1));
        repository.answerInformationRequest(infoId, "segunda", NOW.plusHours(2));

        assertThat(repository.informationHistory(request.getId())).singleElement()
                .satisfies(record -> assertThat(record.responseText()).isEqualTo("primera"));
    }

    @Test
    void evidenceImagesAreStoredNumberedAndServedWithoutLoadingThemInLists() {
        ReturnRequest request = stored(deliveredOrder(buyerId, NOW.minusDays(3)));
        evidence.save(request.getId(), 1, "uno.png", "image/png", "a".repeat(64), new byte[]{1, 2, 3});
        evidence.save(request.getId(), 2, "dos.jpg", "image/jpeg", "b".repeat(64), new byte[]{4, 5});

        assertThat(evidence.summaries(request.getId())).extracting(ReturnEvidenceStorage.EvidenceSummary::fileName,
                ReturnEvidenceStorage.EvidenceSummary::sizeBytes).containsExactly(
                org.assertj.core.groups.Tuple.tuple("uno.png", 3L), org.assertj.core.groups.Tuple.tuple("dos.jpg", 2L));
        assertThat(evidence.find(request.getId(), 2).orElseThrow().data()).containsExactly(4, 5);
        assertThat(evidence.find(request.getId(), 3)).isEmpty();
        assertThatThrownBy(() -> evidence.save(request.getId(), 2, "otra.png", "image/png", "c".repeat(64), new byte[]{9}))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
