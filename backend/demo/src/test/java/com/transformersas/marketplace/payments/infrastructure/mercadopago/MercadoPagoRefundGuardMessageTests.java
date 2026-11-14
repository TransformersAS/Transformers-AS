package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MercadoPagoRefundGuardMessageTests {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SimulatedRefundGateway legacy=mock(SimulatedRefundGateway.class);
    private final MercadoPagoRefundGuard guard=new MercadoPagoRefundGuard(jdbc,legacy);
    @ParameterizedTest @CsvSource({"order-cancel-7,cancelación", "claim-9,reclamación", "return-11,devolución de producto", "other-3,Esta solicitud", "order-cancel-8,Esta solicitud"})
    void mpMessagesDescribeActualScopeAndNeverInvokeSimulator(String key,String context) {
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L))).thenReturn("MP-456");
        assertThatThrownBy(() -> guard.refund(key,7L,new BigDecimal("100")))
            .isInstanceOf(RefundRequestFailedException.class).hasMessageContaining(context)
            .hasMessageContaining("No se ejecutó un reembolso simulado");
        verifyNoInteractions(legacy);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"claim-9","return-11"})
    void claimsAndProductReturnsExplicitlyHaveNoAutomaticReconciliation(String key) {
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L))).thenReturn("MP-456");
        assertThatThrownBy(() -> guard.refund(key,7L,BigDecimal.TEN))
            .hasMessageContaining("no tiene conciliación automática implementada; requiere gestión y verificación");
        verifyNoInteractions(legacy);
    }
    @Test void cancellationMessageDoesNotPromiseEnabledAutomation() {
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L))).thenReturn("MP-456");
        assertThatThrownBy(() -> guard.refund("order-cancel-7",7L,BigDecimal.TEN))
            .hasMessageContaining("solo se realiza si está habilitado y la operación es elegible");
    }
    @Test void nonMpGatewayReceivesOriginalKeyOrderAndAmount() {
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(7L))).thenReturn("SIM-transaction");
        guard.refund("claim-9",7L,BigDecimal.TEN);
        verify(legacy).refund("claim-9",7L,BigDecimal.TEN);
    }
}
