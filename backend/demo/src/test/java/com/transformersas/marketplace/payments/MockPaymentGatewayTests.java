package com.transformersas.marketplace.payments;

import com.transformersas.marketplace.payments.infrastructure.gateway.MockPaymentGateway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MockPaymentGatewayTests {
    private final MockPaymentGateway gateway = new MockPaymentGateway();
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"  "})
    void requiresPaymentMethod(String method) {
        assertThatThrownBy(() -> gateway.process(method)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getReason()).isEqualTo("Debe seleccionar un método de pago");
        });
    }
    @ParameterizedTest @CsvSource({"TEST_UNAVAILABLE,503,La pasarela de pago no está disponible", "UNKNOWN,400,Método de pago inválido"})
    void rejectsUnavailableGatewayAndUnsupportedMethods(String method, int status, String reason) {
        assertThatThrownBy(() -> gateway.process(method)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(status);
            assertThat(ex.getReason()).isEqualTo(reason);
        });
    }
    @ParameterizedTest @CsvSource({"card,APPROVED,Pago aprobado", "test_reject,REJECTED,El pago fue rechazado", "test_pending,PENDING,El pago se encuentra pendiente"})
    void normalizesMethodsAndReturnsDistinctTransactionIds(String method, String status, String message) {
        var result = gateway.process(" " + method + " ");
        assertThat(result.status().name()).isEqualTo(status);
        assertThat(result.message()).isEqualTo(message);
        assertThat(UUID.fromString(result.transactionId()).toString()).isEqualTo(result.transactionId());
        assertThat(gateway.process(method).transactionId()).isNotEqualTo(result.transactionId());
    }
}
