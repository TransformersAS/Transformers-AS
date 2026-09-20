package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.dto.RefreshOutcome;
import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.application.dto.UpdateResult;
import com.transformersas.marketplace.logistics.application.usecase.GetReturnTrackingUseCase;
import com.transformersas.marketplace.logistics.application.usecase.PollActiveTrackingUseCase;
import com.transformersas.marketplace.logistics.application.usecase.ProcessReturnUpdateUseCase;
import com.transformersas.marketplace.logistics.application.usecase.ProcessShipmentUpdateUseCase;
import com.transformersas.marketplace.logistics.application.usecase.RefreshReturnTrackingUseCase;
import com.transformersas.marketplace.logistics.application.usecase.RefreshShipmentTrackingUseCase;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.ShipmentStatus;
import com.transformersas.marketplace.logistics.domain.model.TrackingConflictException;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.domain.repository.OrderTrackingPort;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ramas de concurrencia y de error de los casos de uso de seguimiento que no se pueden provocar de forma
 * determinista con la BD: pérdida del compare-and-set, interbloqueos, aislamiento de fallos en el barrido.
 */
class TrackingUseCaseRetryTests {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShipmentTrackingRepository tracking = mock(ShipmentTrackingRepository.class);
    private final OrderTrackingPort orderTracking = mock(OrderTrackingPort.class);
    private final ReturnShipmentRepository returns = mock(ReturnShipmentRepository.class);
    private final PublishNotificationUseCase notifications = mock(PublishNotificationUseCase.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final LogisticsGateway gateway = mock(LogisticsGateway.class);

    private ProcessShipmentUpdateUseCase processShipment;
    private ProcessReturnUpdateUseCase processReturn;

    private static final Shipment SHIPMENT = new Shipment(1L, 42L, "SIM-order-42", "TRK-42", "order-42",
            ShipmentStatus.CREATED, LocalDateTime.now());
    private static final ReturnShipment RETURN = new ReturnShipment(3L, 77L, 5L, 1L, "SIM-return-77", "TRK-R77",
            ReturnStatus.PICKUP_PENDING, 0, false, null, null, LocalDateTime.now(), LocalDateTime.now());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // La transacción simulada ejecuta el callback tal cual: aquí interesa la lógica de reintento.
        when(transaction.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        processShipment = new ProcessShipmentUpdateUseCase(shipments, tracking, orderTracking, audit, transaction);
        processReturn = new ProcessReturnUpdateUseCase(returns, notifications, audit, events, transaction);
        when(shipments.findByProviderShipmentId("SIM-order-42")).thenReturn(Optional.of(SHIPMENT));
        when(returns.findByProviderReturnId("SIM-return-77")).thenReturn(Optional.of(RETURN));
    }

    private static TrackingUpdate shipmentUpdate(ShipmentEventType type) {
        return new TrackingUpdate("evt-1", "SIM-order-42", "TRK-42", type, NOW, null, null, null);
    }

    private static ReturnTrackingUpdate returnUpdate(ReturnEventType type) {
        return new ReturnTrackingUpdate("evt-1", "SIM-return-77", "TRK-R77", type, NOW, null, null, null);
    }

    private void insertsTheShipmentEventAsNew() {
        var event = new TrackingEvent(99L, 1L, 42L, "evt-1", ShipmentEventType.PICKED_UP, LocalDateTime.now(),
                LocalDateTime.now(), TrackingSource.WEBHOOK, TrackingOutcome.RECORDED, null, null, null, "corr");
        when(tracking.insertEventIfAbsent(any())).thenReturn(new ShipmentTrackingRepository.Insertion(event, true));
    }

    private void insertsTheReturnEventAsNew() {
        var event = new ReturnTrackingEvent(98L, 3L, "evt-1", ReturnEventType.PICKED_UP, LocalDateTime.now(),
                LocalDateTime.now(), TrackingSource.WEBHOOK, TrackingOutcome.RECORDED, null, null, null, "corr");
        when(returns.insertEventIfAbsent(any())).thenReturn(new ReturnShipmentRepository.EventInsertion(event, true));
        when(returns.findById(3L)).thenReturn(Optional.of(RETURN));
    }

    // ---------- Pedidos ----------

    @Test
    void aLostCompareAndSetIsRetriedReadingTheNewStateAndThenSucceeds() {
        insertsTheShipmentEventAsNew();
        when(orderTracking.apply(eq(42L), any())).thenThrow(new TrackingConflictException("El pedido", 42L))
                .thenThrow(new TrackingConflictException("El pedido", 42L)).thenReturn(TrackingOutcome.APPLIED);

        UpdateResult result = processShipment.execute(shipmentUpdate(ShipmentEventType.PICKED_UP), TrackingSource.WEBHOOK);

        assertThat(result).isEqualTo(UpdateResult.APPLIED);
        verify(orderTracking, times(3)).apply(eq(42L), any());
        verify(tracking, times(3)).lock(1L);
    }

    @Test
    void aDeadlockOrLockTimeoutIsRetriedToo() {
        insertsTheShipmentEventAsNew();
        when(orderTracking.apply(eq(42L), any())).thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(TrackingOutcome.RECORDED);

        assertThat(processShipment.execute(shipmentUpdate(ShipmentEventType.IN_TRANSIT), TrackingSource.POLLING))
                .isEqualTo(UpdateResult.RECORDED);

        verify(orderTracking, times(2)).apply(eq(42L), any());
        // RECORDED es el valor con el que ya se insertó el evento: no hace falta actualizarlo.
        verify(tracking, never()).updateEventOutcome(anyLong(), any());
    }

    @Test
    void aPersistentConflictAfterThreeAttemptsAnswersConflictSoTheProviderCanResend() {
        insertsTheShipmentEventAsNew();
        when(orderTracking.apply(eq(42L), any())).thenThrow(new TrackingConflictException("El pedido", 42L));

        assertThatThrownBy(() -> processShipment.execute(shipmentUpdate(ShipmentEventType.PICKED_UP), TrackingSource.WEBHOOK))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(error.code()).isEqualTo("ORDER_STATE_CONFLICT");
                });

        verify(orderTracking, times(3)).apply(eq(42L), any());
    }

    @Test
    void aRepeatedEventDoesNotTouchTheOrder() {
        var event = new TrackingEvent(99L, 1L, 42L, "evt-1", ShipmentEventType.PICKED_UP, LocalDateTime.now(),
                LocalDateTime.now(), TrackingSource.WEBHOOK, TrackingOutcome.APPLIED, null, null, null, "corr");
        when(tracking.insertEventIfAbsent(any())).thenReturn(new ShipmentTrackingRepository.Insertion(event, false));

        assertThat(processShipment.execute(shipmentUpdate(ShipmentEventType.PICKED_UP), TrackingSource.WEBHOOK))
                .isEqualTo(UpdateResult.DUPLICATE);

        verify(orderTracking, never()).apply(anyLong(), any());
        verify(tracking, never()).closeTracking(anyLong());
    }

    @Test
    void aFinalUpdateClosesTheTrackingAndAnOutOfOrderOneOnlyUpdatesTheOutcome() {
        insertsTheShipmentEventAsNew();
        when(orderTracking.apply(eq(42L), any())).thenReturn(TrackingOutcome.APPLIED)
                .thenReturn(TrackingOutcome.OUT_OF_ORDER).thenReturn(TrackingOutcome.APPLIED);

        processShipment.execute(shipmentUpdate(ShipmentEventType.DELIVERED), TrackingSource.WEBHOOK);
        verify(tracking).closeTracking(1L);
        verify(tracking).updateEventOutcome(99L, TrackingOutcome.APPLIED);

        processShipment.execute(shipmentUpdate(ShipmentEventType.DELIVERED), TrackingSource.WEBHOOK);
        verify(tracking, times(1)).closeTracking(1L); // fuera de orden: el envío sigue activo
        verify(tracking).updateEventOutcome(99L, TrackingOutcome.OUT_OF_ORDER);

        processShipment.execute(shipmentUpdate(ShipmentEventType.IN_TRANSIT), TrackingSource.WEBHOOK);
        verify(tracking, times(1)).closeTracking(1L); // aplicado pero no final
    }

    // ---------- Devoluciones ----------

    @Test
    void aLostCompareAndSetOnAReturnIsRetriedAndThenSucceeds() {
        insertsTheReturnEventAsNew();
        when(returns.applyChange(eq(3L), any(), any())).thenReturn(false).thenReturn(true);

        UpdateResult result = processReturn.execute(returnUpdate(ReturnEventType.PICKED_UP), TrackingSource.POLLING);

        assertThat(result).isEqualTo(UpdateResult.APPLIED);
        verify(returns, times(2)).applyChange(eq(3L), eq(ReturnStatus.PICKUP_PENDING), any());
        verify(returns, times(2)).lock(3L);
    }

    @Test
    void aPersistentReturnConflictAfterThreeAttemptsAnswersConflict() {
        insertsTheReturnEventAsNew();
        when(returns.applyChange(eq(3L), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> processReturn.execute(returnUpdate(ReturnEventType.PICKED_UP), TrackingSource.WEBHOOK))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code())
                        .isEqualTo("RETURN_STATE_CONFLICT"));

        verify(returns, times(3)).applyChange(eq(3L), any(), any());
        verify(notifications, never()).execute(any());
    }

    @Test
    void aDeadlockOnAReturnIsRetried() {
        insertsTheReturnEventAsNew();
        when(returns.applyChange(eq(3L), any(), any())).thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(true);

        assertThat(processReturn.execute(returnUpdate(ReturnEventType.PICKED_UP), TrackingSource.WEBHOOK))
                .isEqualTo(UpdateResult.APPLIED);
    }

    // ---------- Consulta: casos límite ----------

    @Test
    void refreshingAShipmentWithoutTrackingStateOrAReturnWithoutStateIsNotTracked() {
        var policy = new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(30), 20);
        var refreshShipment = new RefreshShipmentTrackingUseCase(gateway, shipments, tracking, processShipment, policy);
        var refreshReturn = new RefreshReturnTrackingUseCase(gateway, returns, processReturn,
                mock(GetReturnTrackingUseCase.class), policy);
        when(tracking.findState(1L)).thenReturn(Optional.empty());
        when(returns.findState(3L)).thenReturn(Optional.empty());

        assertThat(refreshShipment.refresh(SHIPMENT, false)).isEqualTo(RefreshOutcome.NOT_TRACKED);
        assertThat(refreshReturn.refresh(RETURN, false)).isEqualTo(RefreshOutcome.NOT_TRACKED);
        verify(gateway, never()).fetchShipmentUpdates(any());
        verify(gateway, never()).fetchReturnUpdates(any());
    }

    @Test
    void refreshingByOrderIdWithoutAShipmentIsNotTracked() {
        var policy = new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(30), 20);
        var refreshShipment = new RefreshShipmentTrackingUseCase(gateway, shipments, tracking, processShipment, policy);
        when(shipments.findByOrderId(500L)).thenReturn(Optional.empty());

        assertThat(refreshShipment.execute(500L)).isEqualTo(RefreshOutcome.NOT_TRACKED);
    }

    // ---------- Barrido: un fallo no detiene a los demás ----------

    @Test
    void aFailureWithOneShipmentOrReturnDoesNotStopTheRestOfTheSweep() {
        var policy = new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(30), 5);
        var refreshShipment = mock(RefreshShipmentTrackingUseCase.class);
        var refreshReturn = mock(RefreshReturnTrackingUseCase.class);
        var poll = new PollActiveTrackingUseCase(tracking, shipments, returns, refreshShipment, refreshReturn, policy);
        Shipment second = new Shipment(2L, 43L, "SIM-order-43", "TRK-43", "order-43", ShipmentStatus.CREATED, LocalDateTime.now());
        when(tracking.findDueForPolling(any(), eq(5))).thenReturn(List.of(
                new TrackingState(1L, true, null, 0), new TrackingState(2L, true, null, 0),
                new TrackingState(9L, true, null, 0)));
        when(shipments.findById(1L)).thenThrow(new IllegalStateException("BD"));
        when(shipments.findById(2L)).thenReturn(Optional.of(second));
        when(shipments.findById(9L)).thenReturn(Optional.empty());
        ReturnShipment another = new ReturnShipment(4L, 78L, 5L, 1L, "SIM-return-78", "TRK-R78",
                ReturnStatus.PICKUP_PENDING, 0, false, null, null, LocalDateTime.now(), LocalDateTime.now());
        when(returns.findDueForPolling(any(), eq(5))).thenReturn(List.of(RETURN, another));
        when(refreshReturn.refresh(eq(RETURN), anyBoolean())).thenThrow(new IllegalStateException("proveedor"));

        int polled = poll.execute();

        assertThat(polled).isEqualTo(2); // el envío 2 y la devolución 78
        verify(refreshShipment).refresh(second, false);
        verify(refreshReturn).refresh(another, false);
    }
}
