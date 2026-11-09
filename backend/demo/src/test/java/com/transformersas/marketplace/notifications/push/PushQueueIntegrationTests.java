package com.transformersas.marketplace.notifications.push;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.notifications.infrastructure.push.*;
import com.transformersas.marketplace.notifications.infrastructure.queue.*;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.testcontainers.containers.GenericContainer;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.Map;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"notifications.queue.enabled=true","notifications.push.enabled=true",
        "notifications.queue.consumer-enabled=false","notifications.queue.initial-delay-ms=3600000","notifications.queue.max-attempts=2"})
class PushQueueIntegrationTests extends AbstractIntegrationTest {
    static final GenericContainer<?> RABBIT=new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER","queue_test").withEnv("RABBITMQ_DEFAULT_PASS","test-not-secret").withExposedPorts(5672);
    static {RABBIT.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host",RABBIT::getHost);r.add("spring.rabbitmq.port",()->RABBIT.getMappedPort(5672));
        r.add("spring.rabbitmq.username",()->"queue_test");r.add("spring.rabbitmq.password",()->"test-not-secret");
        r.add("notifications.push.public-key",()->PushTestKeys.PUBLIC);r.add("notifications.push.private-key",()->PushTestKeys.PRIVATE);
    }
    @Autowired PublishNotificationUseCase publish;
    @Autowired TransactionTemplate tx;
    @Autowired NotificationOutboxPublisher outbox;
    @Autowired NotificationQueueConsumer consumer;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoBean BrowserPushTransport transport;
    long buyer;Session session;
    @BeforeEach void data() throws Exception {
        listeners.stop();
        rabbit.execute(channel->{channel.queuePurge(NotificationQueueConfiguration.QUEUE);channel.queuePurge(NotificationQueueConfiguration.DEAD);return null;});
        reset(transport);when(transport.send(any(),any())).thenReturn(201);
        buyer=createAccount("push-buyer@example.test","COMPRADOR");session=login("push-buyer@example.test");
    }
    String endpoint="https://fcm.googleapis.com/fcm/send/test-endpoint";
    String input() throws Exception {return json.writeValueAsString(Map.of("endpoint",endpoint,"keys",Map.of("p256dh",PushTestKeys.PUBLIC,"auth",PushTestKeys.AUTH)));}
    void subscribe() throws Exception {perform(session,post("/api/push/subscriptions").contentType("application/json").content(input())).andExpect(status().isOk());}
    long notice(String key) {return tx.execute(status->publish.execute(new PublishNotificationCommand(RecipientType.BUYER,buyer,"PRODUCT_ALERT","Alerta de prueba","Producto de prueba","PRODUCT","1",key)).id());}
    String state(long id){return jdbc.queryForObject("SELECT status FROM notification_jobs WHERE notification_id=?",String.class,id);}
    void nextAttempt(long id){jdbc.update("UPDATE notification_jobs SET available_at=CURRENT_TIMESTAMP(6) WHERE notification_id=?",id);}
    @Test void deviceLimitAllowsRenewalButRejectsSixthBrowser() throws Exception {
        for(int i=0;i<5;i++) {endpoint="https://fcm.googleapis.com/fcm/send/device-"+i;subscribe();}
        // Renew an existing subscription even when the account has reached its limit.
        subscribe();
        perform(session,post("/api/push/status").contentType("application/json")
                .content(json.writeValueAsString(Map.of("endpoint",endpoint))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(true));
        perform(session,post("/api/push/status").contentType("application/json")
                .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/not-registered\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(false));
        endpoint="https://fcm.googleapis.com/fcm/send/sixth";
        perform(session,post("/api/push/subscriptions").contentType("application/json").content(input())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions",Integer.class)).isEqualTo(5);
    }
    @Test void queueDeliversThroughRealRabbitConsumerAndDeduplicates() throws Exception {
        subscribe();long id=notice("push-real");assertThat(state(id)).isEqualTo("PENDING");
        listeners.start();outbox.publishPending();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(state(id)).isEqualTo("DONE"));
        consumer.receive(Long.toString(id).getBytes());verify(transport,times(1)).send(any(),any());
        assertThat(jdbc.queryForObject("SELECT status FROM push_deliveries WHERE notification_id=?",String.class,id)).isEqualTo("SENT");
    }
    @Test void rollbackDoesNotLeaveAnOutboxEvent() {
        assertThatThrownBy(()->tx.executeWithoutResult(status->{notice("push-rollback");throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_jobs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class)).isZero();
    }
    @Test void brokerFailureKeepsTheOutboxRecoverable() {
        long id=notice("push-broker-down");
        var template=mock(RabbitTemplate.class);when(template.execute(any())).thenThrow(new IllegalStateException("offline"));
        new NotificationOutboxPublisher(jdbc,template,tx).publishPending();assertThat(state(id)).isEqualTo("PENDING");
        outbox.publishPending();assertThat(state(id)).isEqualTo("PUBLISHED");
        assertThat(rabbit.receive(NotificationQueueConfiguration.QUEUE,5000)).isNotNull();
    }
    @Test void retriesThenRoutesFailuresToTheDeadLetterQueueWithoutLosingInbox() throws Exception {
        subscribe();when(transport.send(any(),any())).thenReturn(503);long id=notice("push-fail");
        listeners.start();outbox.publishPending();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(state(id)).isEqualTo("RETRY"));
        nextAttempt(id);outbox.publishPending();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(state(id)).isEqualTo("FAILED"));
        assertThat(rabbit.receive(NotificationQueueConfiguration.DEAD,5000)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=?",Integer.class,id)).isEqualTo(1);
        verify(transport,times(2)).send(any(),any());
    }
    @Test void retrySkipsDevicesAlreadySentAndRemovesExpiredSubscriptions() throws Exception {
        subscribe();endpoint="https://fcm.googleapis.com/fcm/send/second-endpoint";subscribe();
        when(transport.send(any(),any())).thenReturn(201,503,201);
        long id=notice("push-partial");consumer.receive(Long.toString(id).getBytes());assertThat(state(id)).isEqualTo("RETRY");
        nextAttempt(id);consumer.receive(Long.toString(id).getBytes());assertThat(state(id)).isEqualTo("DONE");
        verify(transport,times(3)).send(any(),any());
        when(transport.send(any(),any())).thenReturn(410);
        consumer.receive(Long.toString(notice("push-expired")).getBytes());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions",Integer.class)).isZero();
    }
    @Test void subscriptionsRequireCsrfAndDisappearOnLogout() throws Exception {
        mvc.perform(get("/api/push/config")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/push/subscriptions").cookie(session.cookie()).contentType("application/json").content(input())).andExpect(status().isForbidden());
        subscribe();perform(session,post("/api/auth/logout")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions",Integer.class)).isZero();
        consumer.receive(Long.toString(notice("push-after-logout")).getBytes());verifyNoInteractions(transport);
    }
    @Test void otherAccountCannotRemoveOrStealSubscription() throws Exception {
        subscribe();var other=sessionWithRole("push-other@example.test","COMPRADOR");
        perform(other,delete("/api/push/subscriptions").contentType("application/json").content(json.writeValueAsString(Map.of("endpoint",endpoint)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions",Integer.class)).isEqualTo(1);
        perform(other,post("/api/push/subscriptions").contentType("application/json").content(input())).andExpect(status().isConflict());
    }
    @Test void expiredSessionCannotReceivePush() throws Exception {
        subscribe();jdbc.update("UPDATE SPRING_SESSION SET EXPIRY_TIME=0");
        consumer.receive(Long.toString(notice("push-expired-session")).getBytes());verifyNoInteractions(transport);
    }
    @Test void concurrentAccountsCannotReassignTheSameEndpoint() throws Exception {
        var other=sessionWithRole("push-concurrent@example.test","COMPRADOR");String body=input();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> first=()->{start.await();return mvc.perform(session.apply(post("/api/push/subscriptions").contentType("application/json").content(body))).andReturn().getResponse().getStatus();};
            java.util.concurrent.Callable<Integer> second=()->{start.await();return mvc.perform(other.apply(post("/api/push/subscriptions").contentType("application/json").content(body))).andReturn().getResponse().getStatus();};
            var a=pool.submit(first);var b=pool.submit(second);start.countDown();
            assertThat(java.util.List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions p JOIN SPRING_SESSION s ON s.PRIMARY_ID=p.session_primary_id JOIN user_accounts a ON a.id=p.account_id WHERE s.PRINCIPAL_NAME=a.email",Integer.class)).isEqualTo(1);
    }
}
