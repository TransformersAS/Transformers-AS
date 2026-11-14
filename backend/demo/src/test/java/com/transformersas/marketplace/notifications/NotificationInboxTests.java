package com.transformersas.marketplace.notifications;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationInboxTests extends AbstractIntegrationTest {
    @Autowired PublishNotificationUseCase publish;
    @Autowired TransactionTemplate tx;
    @Autowired SimulatedExternalNotificationGateway external;
    private void notice(RecipientType type,long recipient,String key) {
        tx.executeWithoutResult(s -> publish.execute(new PublishNotificationCommand(type,recipient,"TEST","Aviso "+key,
            "Mensaje seguro","ORDER","42",key)));
    }
    @Test void scopedPaginationStableOrderNewSessionAndNoReadSideEffects() throws Exception {
        var buyer=sessionWithRole("inbox@example.test","COMPRADOR"); long account=accountIdOf("inbox@example.test");
        var other=sessionWithRole("other-inbox@example.test","COMPRADOR");
        notice(RecipientType.BUYER,account,"a");notice(RecipientType.BUYER,account,"b");notice(RecipientType.BUYER,account,"c");
        notice(RecipientType.BUYER,accountIdOf("other-inbox@example.test"),"private");notice(RecipientType.BUYER,account,"c");
        jdbc.update("UPDATE notifications SET created_at='2026-01-01 12:00:00'");
        var first=perform(buyer,get("/api/notifications").param("size","2").param("recipientId","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
        var rows=json.readTree(first).path("items");assertThat(rows.get(0).path("title").asString()).isEqualTo("Aviso c");
        assertThat(rows.get(1).path("title").asString()).isEqualTo("Aviso b");
        assertThat(first).doesNotContain("eventKey","correlation","externalStatus","lastError","recipientId");
        perform(buyer,get("/api/notifications").param("size","2").param("page","1"))
            .andExpect(jsonPath("$.items[0].title").value("Aviso a")).andExpect(jsonPath("$.hasMore").value(false));
        perform(login("inbox@example.test"),get("/api/notifications").param("size","2"))
            .andExpect(content().json(first));
        perform(other,get("/api/notifications")).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Aviso private"));
        assertThat(count("notifications")).isEqualTo(4);assertThat(count("orders")).isZero();assertThat(count("refunds")).isZero();
    }
    @Test void sellerUsesOwnedStoreNotAccountAndCannotSelectAnotherStore() throws Exception {
        seedStore(20,"Otra tienda");var seller=sellerOfStore("inbox-seller@example.test",20);
        notice(RecipientType.STORE,20,"owned");notice(RecipientType.STORE,1,"foreign");
        perform(seller,get("/api/seller/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].title").value("Aviso owned"));
        performAsSeller(seller,1,get("/api/seller/notifications")).andExpect(status().isForbidden());
        perform(seller,get("/api/notifications")).andExpect(status().isForbidden());
        var buyer=sessionWithRole("inbox-buyer@example.test","COMPRADOR");
        perform(buyer,get("/api/seller/notifications")).andExpect(status().isForbidden());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/seller/notifications")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings={"-1","100001","bad"})
    void invalidPaginationIsControlled(String page) throws Exception {
        var buyer=sessionWithRole("paging@example.test","COMPRADOR");
        perform(buyer,get("/api/notifications").param("page",page)).andExpect(status().isBadRequest());
        perform(buyer,get("/api/notifications").param("size","51")).andExpect(status().isBadRequest());
    }
    @Test void directPurchaseAndCancellationNotifyBothRecipientsDespiteExternalFailure() throws Exception {
        external.reset();external.setMode(SimulatedExternalNotificationGateway.Mode.UNAVAILABLE);
        try {
            var buyer=sessionWithRole("purchase-inbox@example.test","COMPRADOR");long product=seedProduct(1,"Producto",5,"1000");
            long order=checkoutOrder(buyer,product,1);
            perform(buyer,post("/api/orders/{id}/cancellation",order).contentType("application/json")
                .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andExpect(status().isOk());
            await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE external_status='FAILED'",Integer.class)).isEqualTo(4));
            int calls=external.requestCount();
            perform(buyer,get("/api/notifications")).andExpect(jsonPath("$.items.length()").value(2));
            var seller=sellerOfStore("purchase-store@example.test",1);
            perform(seller,get("/api/seller/notifications")).andExpect(jsonPath("$.items.length()").value(2));
            assertThat(external.requestCount()).isEqualTo(calls);
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product)).isEqualTo(5);
            assertThat(count("order_cancellations")).isEqualTo(1);assertThat(count("refunds")).isEqualTo(1);
        } finally {external.reset();}
    }
}
