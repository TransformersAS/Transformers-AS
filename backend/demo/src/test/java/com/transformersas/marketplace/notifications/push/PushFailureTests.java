package com.transformersas.marketplace.notifications.push;

import com.transformersas.marketplace.notifications.infrastructure.push.*;
import com.transformersas.marketplace.notifications.infrastructure.queue.*;
import com.transformersas.marketplace.notifications.domain.model.*;
import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;
import com.transformersas.marketplace.notifications.application.usecase.DispatchExternalNotificationUseCase;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushFailureTests {
    PushSettings settings(boolean enabled) {return new PushSettings(enabled,true,"public","private","mailto:test@example.test");}

    @Test void disabledOrUnconfiguredPushNeverSendsAndSecretsAreRedacted() {
        var jdbc=mock(JdbcTemplate.class);var subs=mock(PushSubscriptionService.class);var transport=mock(BrowserPushTransport.class);
        assertThat(new PushDeliveryService(settings(false),subs,transport,jdbc,new ObjectMapper()).deliver(null)).isTrue();
        var incomplete=new PushSettings(true,true,"","private-secret","mailto:test@example.test");
        assertThatThrownBy(()->new PushDeliveryService(incomplete,subs,transport,jdbc,new ObjectMapper()).deliver(null)).isInstanceOf(IllegalStateException.class);
        assertThat(incomplete.toString()).doesNotContain("private-secret");
        assertThat(new PushSubscriptionService.Keys("private-key","auth-secret").toString()).doesNotContain("private-key","auth-secret");
        assertThat(new PushSubscriptionService.Input("endpoint-secret",null).toString()).doesNotContain("endpoint-secret");
        assertThat(new PushController.Removal("endpoint-secret").toString()).doesNotContain("endpoint-secret");
        assertThat(new PushSubscriptionService.Subscription("id","endpoint-secret","private-key","auth-secret").toString()).doesNotContain("endpoint-secret","private-key","auth-secret");
        verifyNoInteractions(transport);
    }

    @Test void interruptedDeliveryIsRetryableAndPreservesInterruptFlag() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var subs=mock(PushSubscriptionService.class);var transport=mock(BrowserPushTransport.class);
        var notice=mock(Notification.class);when(notice.id()).thenReturn(1L);when(notice.recipientType()).thenReturn(RecipientType.BUYER);when(notice.recipientId()).thenReturn(2L);
        when(subs.recipients("BUYER",2L)).thenReturn(List.of(new PushSubscriptionService.Subscription("device","endpoint","key","auth")));
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(1L),eq("device"))).thenReturn("PENDING");
        when(transport.send(any(),any())).thenThrow(new InterruptedException());
        try {
            assertThat(new PushDeliveryService(settings(true),subs,transport,jdbc,new ObjectMapper()).deliver(notice)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(jdbc).update(contains("status='FAILED'"),eq(1L),eq("device"));
        } finally {Thread.interrupted();}
    }

    @Test void sellerPushUsesOwnedStoreAndRejectsUnsupportedActors() {
        var subs=mock(PushSubscriptionService.class);var actor=mock(CurrentActorProvider.class);when(actor.storeId()).thenReturn(9L);
        var p=mock(AccountPrincipal.class);when(p.accountId()).thenReturn(2L);when(p.activeRole()).thenReturn(Role.VENDEDOR);
        var request=new MockHttpServletRequest();String session=request.getSession().getId();
        when(subs.save(eq(2L),eq("STORE"),eq(9L),eq(session),isNull())).thenReturn("device");
        when(subs.registered(2L,session,"STORE",9L,"endpoint")).thenReturn(true);
        var controller=new PushController(settings(true),subs,actor);
        assertThat(controller.config()).containsEntry("enabled",true);
        assertThat(controller.subscribe(p,request,null)).containsEntry("id","device");
        assertThat(controller.status(p,request,new PushController.Removal("endpoint"))).containsEntry("registered",true);
        assertThatThrownBy(()->controller.subscribe(null,request,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.remove(null,null)).isInstanceOf(ResponseStatusException.class);
        when(p.activeRole()).thenReturn(Role.ADMIN);
        assertThatThrownBy(()->controller.subscribe(p,request,null)).isInstanceOf(ResponseStatusException.class);
        assertThat(new PushController(settings(false),subs,actor).config()).containsEntry("publicKey","");
    }

    @Test void invalidMessagesAndRetryConfigurationAreRejected() {
        var jdbc=mock(JdbcTemplate.class);var repo=mock(NotificationRepository.class);var external=mock(DispatchExternalNotificationUseCase.class);var push=mock(PushDeliveryService.class);
        for(int attempts:new int[]{0,11}) assertThatThrownBy(()->new NotificationQueueConsumer(jdbc,repo,external,push,attempts)).isInstanceOf(IllegalArgumentException.class);
        var consumer=new NotificationQueueConsumer(jdbc,repo,external,push,2);
        for(String body:List.of("0","-1","abc","9999999999999999999"))
            assertThatThrownBy(()->consumer.receive(body.getBytes())).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        when(jdbc.update(anyString(),eq(1L))).thenReturn(1);
        when(repo.findById(1L)).thenReturn(Optional.empty());
        consumer.receive("1".getBytes());verifyNoInteractions(external,push);
    }

    @Test void consumerExceptionRecordsRetryAndAdminRetryRejectsNonFailedJobs() {
        var jdbc=mock(JdbcTemplate.class);var repo=mock(NotificationRepository.class);var external=mock(DispatchExternalNotificationUseCase.class);var push=mock(PushDeliveryService.class);
        when(jdbc.update(anyString(),eq(1L))).thenReturn(1);
        when(repo.findById(1L)).thenThrow(new IllegalStateException("unavailable"));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L))).thenReturn(1);
        new NotificationQueueConsumer(jdbc,repo,external,push,2).receive("1".getBytes());
        verify(jdbc).update(contains("SET status=?"),eq("RETRY"),eq(10),anyString(),eq(1L));
        var retry=new NotificationRetryController(jdbc);
        when(jdbc.update(contains("status='PENDING'"),eq(1L))).thenReturn(1,0);
        retry.retry(1L);
        assertThatThrownBy(()->retry.retry(1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void subscriptionRejectsMalformedKeysEndpointsAndMissingConfigurationBeforeWriting() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new PushSubscriptionService(jdbc,settings(true));
        assertThatThrownBy(()->new PushSubscriptionService(jdbc,settings(false)).save(1L,"BUYER",1L,"session",null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.save(1L,"BUYER",1L,"session",null)).isInstanceOf(ResponseStatusException.class);
        var keys=java.util.Arrays.asList(null,new PushSubscriptionService.Keys(null,"a"),
                new PushSubscriptionService.Keys("bad!","a"),new PushSubscriptionService.Keys("a".repeat(151),"a"),
                new PushSubscriptionService.Keys(PushTestKeys.PUBLIC,""),new PushSubscriptionService.Keys("BA","a"));
        for(var key:keys) assertThatThrownBy(()->service.save(1L,"BUYER",1L,"session",
                new PushSubscriptionService.Input("https://fcm.googleapis.com/send/test",key))).isInstanceOf(ResponseStatusException.class);
        for(String endpoint:java.util.Arrays.asList(null,"x".repeat(2049),"http://fcm.googleapis.com/x","https://localhost/x",
                "https://user@fcm.googleapis.com/x","https://fcm.googleapis.com/x#fragment","https://fcm.googleapis.com:444/x"))
            assertThatThrownBy(()->PushSubscriptionService.validateEndpoint(endpoint)).isInstanceOf(ResponseStatusException.class);
        for(String host:List.of("fcm.googleapis.com","updates.push.services.mozilla.com","web.push.apple.com","test.notify.windows.com"))
            assertThat(PushSubscriptionService.validateEndpoint("https://"+host+"/x").getHost()).isEqualTo(host);
        assertThatThrownBy(()->service.remove(1L,null)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.registered(1L,"session","BUYER",1L,null)).isFalse();
        verifyNoInteractions(jdbc);
    }
}
