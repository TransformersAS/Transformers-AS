package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H08: consulta HTTP real, MySQL efímero, sin proveedor real. */
class BuyerRefundTrackingTests extends MercadoPagoH05TestSupport {
    @Autowired MercadoPagoFinancialService financial;
    private long cancelled() throws Exception {
        var checkout = start(); payment(checkout, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(checkout.id()); assertThat(cancel(id)).isEqualTo(200);
        return id;
    }
    private void external(long order, String state, boolean confirmed, boolean link) {
        Long legacy = jdbc.queryForObject("SELECT id FROM refunds WHERE order_id=?", Long.class, order);
        jdbc.update("""
            INSERT INTO external_payment_refunds(payment_id,idempotency_key,amount,reason,status,
                legacy_refund_id,externally_confirmed,confirmed_at,last_error)
            VALUES ('456',UUID(),200000,'CANCELLED_ORDER',?,?,?,
                CASE WHEN ? THEN '2026-01-02 12:00:00' ELSE NULL END,'SECRET_TECHNICAL_ERROR')
            """, state, link ? legacy : null, confirmed, confirmed);
    }
    private JsonNode detail(Session session, long id) throws Exception {
        return json.readTree(perform(session,get("/api/orders/{id}",id)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @CsvSource({
        "INTENT,false,INTENT,Solicitud registrada", "SUBMITTING,false,SUBMITTING,Verificando devolución",
        "UNKNOWN,false,UNKNOWN,Verificando devolución", "PENDING,false,PENDING,Devolución en proceso",
        "CONFIRMED,true,CONFIRMED,Devolución confirmada", "FAILED,false,FAILED,No se pudo completar",
        "EXCEPTION,false,EXCEPTION,Requiere revisión",
        "EXCEPTION,true,CONFIRMED_REVIEW,Devolución confirmada; registro en revisión",
        "CONFIRMED,false,EXCEPTION,Requiere revisión"})
    void externalStateWinsOverLocalFailureWithoutDuplicationOrInternalData(String state, boolean confirmed,
            String expected, String label) throws Exception {
        long id=cancelled(); external(id,state,confirmed,true); clearInvocations(provider);
        var before=jdbc.queryForMap("SELECT status,payment_status,total FROM orders WHERE id=?",id);
        int stockBefore=stock(); long events=count("external_refund_events");
        var result=detail(buyer,id); var rows=result.path("refunds"); assertThat(rows.size()).isEqualTo(1);
        var row=rows.get(0);
        assertThat(row.path("status").asString()).isEqualTo(expected);
        assertThat(row.path("label").asString()).isEqualTo(label);
        assertThat(row.path("reason").asString()).isEqualTo("Cancelación del pedido");
        assertThat(row.path("amount").decimalValue()).isEqualByComparingTo("200000");
        assertThat(row.path("currency").asString()).isEqualTo("COP");
        assertThat(row.path("requestedAt").isNull()).isFalse();
        assertThat(row.path("updatedAt").isNull()).isFalse();
        assertThat(row.path("confirmedAt").isNull()).isEqualTo(!confirmed);
        assertThat(row.properties().stream().map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder(
            "reason","amount","currency","status","label","message","requestedAt","updatedAt","confirmedAt");
        assertThat(result.toString()).doesNotContain("SECRET_TECHNICAL_ERROR","collector_id","lease_token","idempotency_key");
        assertThat(detail(buyer,id)).isEqualTo(result);
        var newSession=login("h05-buyer@example.test");
        assertThat(detail(newSession,id)).isEqualTo(result);
        assertThat(jdbc.queryForMap("SELECT status,payment_status,total FROM orders WHERE id=?",id)).isEqualTo(before);
        assertThat(stock()).isEqualTo(stockBefore); assertThat(count("external_refund_events")).isEqualTo(events);
        assertThat(count("order_cancellations")).isEqualTo(1); verifyNoInteractions(provider);
    }
    @Test void fallbackDeduplicatesCancellationEvenWhenLegacyLinkIsMissing() throws Exception {
        long id=cancelled(); external(id,"PENDING",false,false);
        assertThat(detail(buyer,id).path("refunds").size()).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"PENDING,PENDING", "FAILED,FAILED", "COMPLETED,SIMULATED"})
    void localOnlyResultsHaveNoInventedCurrencyOrConfirmation(String local,String expected) throws Exception {
        long id=cancelled();
        jdbc.update("UPDATE refunds SET status=?,provider_reference='SIM-REFUND-private' WHERE order_id=?",local,id);
        var row=detail(buyer,id).path("refunds").get(0);
        assertThat(row.path("status").asString()).isEqualTo(expected);
        assertThat(row.path("currency").isNull()).isTrue(); assertThat(row.path("confirmedAt").isNull()).isTrue();
        assertThat(row.toString()).doesNotContain("SIM-REFUND-private");
        if (local.equals("COMPLETED")) assertThat(row.path("message").asString()).contains("no acredita");
    }
    @Test void emptyHistoryAndAccessControl() throws Exception {
        var checkout=start(); payment(checkout,"456","approved"); assertThat(webhook("456")).isEqualTo(200);
        long id=orderId(checkout.id()); assertThat(detail(buyer,id).path("refunds").size()).isZero();
        var other=sessionWithRole("other-h08@example.test","COMPRADOR");
        perform(other,get("/api/orders/{id}",id)).andExpect(status().isNotFound());
        perform(buyer,get("/api/orders/99999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}",id)).andExpect(status().isUnauthorized());
        var seller=sessionWithRole("seller-h08@example.test","VENDEDOR");
        perform(seller,get("/api/orders/{id}",id)).andExpect(status().isForbidden());
    }
    @Test void laterProviderReconciliationIsVisibleOnNextRead() throws Exception {
        long id=cancelled(); assertThat(detail(buyer,id).path("refunds").get(0).path("status").asString()).isEqualTo("FAILED");
        when(provider.refunds("456")).thenReturn(List.of());
        when(provider.requestFullRefund(eq("456"),anyString())).thenAnswer(call -> {
            var data=new java.util.HashMap<String,Object>(json.convertValue(payments.get("456"),java.util.Map.class));
            data.put("status","refunded"); payments.put("456",json.valueToTree(data));
            return json.valueToTree(java.util.Map.of("id",999,"payment_id",456,"amount",200000,"status","approved"));
        });
        assertThat(financial.recoverBatch()).isEqualTo(1);
        clearInvocations(provider); var result=detail(buyer,id);
        assertThat(result.path("refunds").size()).isEqualTo(1);
        assertThat(result.path("refunds").get(0).path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(result.path("paymentStatus").asString()).isEqualTo("REFUNDED"); verifyNoInteractions(provider);
    }
    @Test void unfulfilledPaymentIsNeverAttachedToAnUnrelatedOrder() throws Exception {
        var checkout=start(); expire(); payment(checkout,"456","approved"); assertThat(webhook("456")).isEqualTo(200);
        jdbc.update("INSERT INTO external_payment_refunds(payment_id,idempotency_key,amount,reason,status) VALUES ('456',UUID(),200000,'NO_ORDER','UNKNOWN')");
        long unrelated=seedOrder(1,"CONFIRMED",product,1,"100");
        jdbc.update("UPDATE orders SET account_id=? WHERE id=?",accountId,unrelated);
        assertThat(detail(buyer,unrelated).path("refunds").size()).isZero();
    }
}
