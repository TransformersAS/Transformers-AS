package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FinancialNotificationTests extends MercadoPagoH05TestSupport {
    @Autowired MercadoPagoFinancialService financial;
    @Autowired MercadoPagoPaymentJournal journal;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrderNotificationPublisher notices;
    @Test void purchaseAndVerifiedRefundNotifyBuyerAndStoreExactlyOnce() throws Exception {
        var checkout=start();payment(checkout,"456","approved");assertThat(webhook("456")).isEqualTo(200);
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='PURCHASED'",Integer.class)).isEqualTo(2);
        long id=orderId(checkout.id());assertThat(cancel(id)).isEqualTo(200);
        payment(checkout,"456","refunded");assertThat(webhook("456")).isEqualTo(200);assertThat(webhook("456")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_CONFIRMED'",Integer.class)).isEqualTo(2);
        perform(buyer,get("/api/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        var seller=sellerOfStore("financial-seller@example.test",1);
        perform(seller,get("/api/seller/notifications")).andExpect(jsonPath("$.items.length()").value(3));
        assertThat(stock()).isEqualTo(10);assertThat(count("refunds")).isEqualTo(1);
        verify(provider,never()).requestFullRefund(anyString(),anyString());
    }
    @Test void uncertainAttemptsNotifyOnlyOnTerminalReviewAndNoOrderGetsNoFictitiousReference() throws Exception {
        var checkout=start();expire();payment(checkout,"456","approved");assertThat(webhook("456")).isEqualTo(200);
        when(provider.refunds("456")).thenReturn(List.of());
        when(provider.requestFullRefund(anyString(),anyString())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY));
        var worker=new MercadoPagoFinancialService(jdbc,provider,journal,transactions,2,10,notices);
        worker.recoverBatch();assertThat(count("notifications")).isZero();
        jdbc.update("UPDATE external_payment_refunds SET next_attempt_at=NOW(6)");worker.recoverBatch();worker.recoverBatch();
        assertThat(count("notifications")).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT type,recipient_type,reference_type FROM notifications");
        assertThat(row).containsAllEntriesOf(Map.of("type","REFUND_REVIEW","recipient_type","BUYER","reference_type","CHECKOUT"));
        perform(buyer,get("/api/notifications")).andExpect(jsonPath("$.items[0].orderId").isEmpty());
        assertThat(count("orders")).isZero();verify(provider,times(1)).requestFullRefund(anyString(),anyString());
    }
}
