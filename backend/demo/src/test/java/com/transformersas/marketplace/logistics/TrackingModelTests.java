package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.dto.ReturnViewer;
import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validaciones y reglas puras del modelo de seguimiento logístico (CU-24 y CU-25). */
class TrackingModelTests {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    private static TrackingUpdate update(String eventId, String shipmentId, String trackingCode,
                                         ShipmentEventType type, Instant at) {
        return new TrackingUpdate(eventId, shipmentId, trackingCode, type, at, null, null, null);
    }

    // ---------- Tipos de actualización ----------

    @Test
    void shipmentEventTypesAreParsedLeniently() {
        assertThat(ShipmentEventType.parse(" picked_up ")).contains(ShipmentEventType.PICKED_UP);
        assertThat(ShipmentEventType.parse("DELIVERY_ATTEMPT_FAILED")).contains(ShipmentEventType.DELIVERY_ATTEMPT_FAILED);
        assertThat(ShipmentEventType.parse("teleported")).isEmpty();
        assertThat(ShipmentEventType.parse(null)).isEmpty();
        assertThat(ShipmentEventType.parse("")).isEmpty();
    }

    @Test
    void onlyDeliveredAndReturnedToSellerCloseTheTracking() {
        for (ShipmentEventType type : ShipmentEventType.values()) {
            boolean closes = type == ShipmentEventType.DELIVERED || type == ShipmentEventType.RETURNED_TO_SELLER;
            assertThat(type.closesTracking()).as(type.name()).isEqualTo(closes);
        }
    }

    @Test
    void returnEventTypesAreParsedLeniently() {
        assertThat(ReturnEventType.parse("delivered_to_seller")).contains(ReturnEventType.DELIVERED_TO_SELLER);
        assertThat(ReturnEventType.parse(" incident")).contains(ReturnEventType.INCIDENT);
        assertThat(ReturnEventType.parse("nope")).isEmpty();
        assertThat(ReturnEventType.parse(null)).isEmpty();
    }

    // ---------- TrackingUpdate ----------

    @Test
    void aValidUpdateKeepsItsFields() {
        var update = new TrackingUpdate("evt-1.a:b_c", "SIM-order-1", "TRK-1", ShipmentEventType.DELIVERED, NOW,
                "  Entregado  ", " Bogotá ", new TrackingEvidence("SIGNATURE", " POD-1 "));

        assertThat(update.description()).isEqualTo("Entregado");
        assertThat(update.location()).isEqualTo("Bogotá");
        assertThat(update.evidence().reference()).isEqualTo("POD-1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "con espacio", "ñandú", "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij"})
    void anInvalidEventIdIsRejected(String eventId) {
        assertThatThrownBy(() -> update(eventId, "SIM-order-1", "TRK-1", ShipmentEventType.PICKED_UP, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void aBlankShipmentReferenceOrTrackingCodeIsRejected(String value) {
        assertThatThrownBy(() -> update("evt-1", value, "TRK-1", ShipmentEventType.PICKED_UP, NOW))
                .hasMessageContaining("shipmentId");
        assertThatThrownBy(() -> update("evt-1", "SIM-order-1", value, ShipmentEventType.PICKED_UP, NOW))
                .hasMessageContaining("trackingCode");
    }

    @Test
    void referencesLongerThanTheColumnAreRejected() {
        assertThatThrownBy(() -> update("evt-1", "s".repeat(101), "TRK-1", ShipmentEventType.PICKED_UP, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingTypeOrDateIsRejected() {
        assertThatThrownBy(() -> update("evt-1", "SIM-order-1", "TRK-1", null, NOW)).hasMessageContaining("type");
        assertThatThrownBy(() -> update("evt-1", "SIM-order-1", "TRK-1", ShipmentEventType.PICKED_UP, null))
                .hasMessageContaining("occurredAt");
    }

    @Test
    void freeTextIsTrimmedTruncatedAndBlankBecomesNull() {
        var update = new TrackingUpdate("evt-1", "SIM-order-1", "TRK-1", ShipmentEventType.PICKED_UP, NOW,
                "d".repeat(700), "   ", new TrackingEvidence("t".repeat(50), "r".repeat(900)));

        assertThat(update.description()).hasSize(500);
        assertThat(update.location()).isNull();
        assertThat(update.evidence().type()).hasSize(32);
        assertThat(update.evidence().reference()).hasSize(500);
    }

    // ---------- ReturnTrackingUpdate ----------

    @Test
    void returnUpdatesApplyTheSameValidations() {
        var valid = new ReturnTrackingUpdate("evt-1", "SIM-return-1", "TRK-R1", ReturnEventType.PICKED_UP, NOW, " ok ",
                null, new TrackingEvidence("CODE", "x"));
        assertThat(valid.description()).isEqualTo("ok");

        Function<String, ReturnTrackingUpdate> withEvent = id -> new ReturnTrackingUpdate(id, "SIM-return-1", "TRK-R1",
                ReturnEventType.PICKED_UP, NOW, null, null, null);
        assertThatThrownBy(() -> withEvent.apply("mal id")).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new ReturnTrackingUpdate("evt-1", " ", "TRK-R1", ReturnEventType.PICKED_UP, NOW, null,
                null, null)).hasMessageContaining("returnId");
        assertThatThrownBy(() -> new ReturnTrackingUpdate("evt-1", "SIM-return-1", null, ReturnEventType.PICKED_UP, NOW,
                null, null, null)).hasMessageContaining("trackingCode");
        assertThatThrownBy(() -> new ReturnTrackingUpdate("evt-1", "SIM-return-1", "TRK-R1", null, NOW, null, null, null))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new ReturnTrackingUpdate("evt-1", "SIM-return-1", "TRK-R1", ReturnEventType.PICKED_UP,
                null, null, null, null)).hasMessageContaining("occurredAt");
    }

    // ---------- Quién puede ver una devolución ----------

    @Test
    void aReturnIsVisibleOnlyToItsBuyerOrItsStore() {
        LocalDateTime now = LocalDateTime.now();
        var shipment = new ReturnShipment(1L, 10L, 5L, 7L, "SIM-return-10", "TRK-R10", ReturnStatus.PICKUP_PENDING, 0,
                false, null, null, now, now);

        assertThat(ReturnViewer.buyer(5L).canView(shipment)).isTrue();
        assertThat(ReturnViewer.buyer(6L).canView(shipment)).isFalse();
        assertThat(ReturnViewer.seller(7L).canView(shipment)).isTrue();
        assertThat(ReturnViewer.seller(8L).canView(shipment)).isFalse();
        assertThat(new ReturnViewer(null, null).canView(shipment)).isFalse();
    }

    // ---------- Estado de consulta y política ----------

    @Test
    void aPollWithFailuresIsFlagged() {
        assertThat(new TrackingState(1L, true, null, 0).lastPollFailed()).isFalse();
        assertThat(new TrackingState(1L, true, LocalDateTime.now(), 2).lastPollFailed()).isTrue();
    }

    @Test
    void theTrackingPolicyRejectsInvalidValues() {
        assertThat(new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(30), 20).batchSize()).isEqualTo(20);
        assertThatThrownBy(() -> new TrackingPolicy(null, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackingPolicy(Duration.ofSeconds(-1), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackingPolicy(Duration.ZERO, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackingPolicy(Duration.ZERO, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
