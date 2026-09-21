package com.transformersas.marketplace.shared;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RNF-009: la auditoría comparte la transacción del cambio y lleva el id de correlación. */
class AuditRecorderTests extends AbstractIntegrationTest {

    @Autowired AuditRecorder audit;
    @Autowired TransactionTemplate transaction;

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    @Test
    void recordsActorActionEntityOutcomeAndCorrelationId() {
        MDC.put("correlationId", "audit-corr-1");

        audit.record(ActorType.SELLER, 42L, "ORDER_TEST", "ORDER", 7L, AuditOutcome.SUCCESS, Map.of("k", "v"));

        var row = jdbc.queryForMap("SELECT * FROM audit_events");
        assertThat(row).containsEntry("actor_type", "SELLER").containsEntry("actor_id", 42L)
                .containsEntry("action", "ORDER_TEST").containsEntry("entity_type", "ORDER")
                .containsEntry("entity_id", "7").containsEntry("outcome", "SUCCESS")
                .containsEntry("correlation_id", "audit-corr-1");
        assertThat(row.get("occurred_at")).isNotNull();
        assertThat((String) row.get("details")).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void rollbackOfTheCallerDiscardsTheAuditEntry() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            audit.record(ActorType.SYSTEM, null, "ORDER_TEST", "ORDER", 1L, AuditOutcome.SUCCESS, null);
            throw new IllegalStateException("fallo inyectado");
        })).hasMessage("fallo inyectado");

        assertThat(count("audit_events")).isZero();
    }

    @Test
    void commitOfTheCallerKeepsTheAuditEntry() {
        transaction.executeWithoutResult(status ->
                audit.record(ActorType.SYSTEM, null, "ORDER_TEST", "ORDER", 1L, AuditOutcome.PENDING, null));

        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void oversizedDetailsAreTruncatedInsteadOfFailing() {
        audit.record(ActorType.SYSTEM, null, "ORDER_TEST", "ORDER", 1L, AuditOutcome.FAILURE,
                Map.of("big", "x".repeat(5000)));

        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(details) FROM audit_events", Integer.class))
                .isEqualTo(2000);
    }
}
