package com.transformersas.marketplace.notifications.push;

import com.transformersas.marketplace.notifications.infrastructure.push.*;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.mockito.ArgumentCaptor;

class WebPushTransportTests {
    @Test void encryptsPayloadUsesVapidAndBoundsHttpTimeout() throws Exception {
        var client=mock(HttpClient.class);HttpResponse<Object> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        doReturn(response).when(client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        var settings=new PushSettings(true,true,PushTestKeys.PUBLIC,PushTestKeys.PRIVATE,"mailto:admin@example.com");
        var sender=new WebPushTransport(settings,client);
        int result=sender.send(new PushSubscriptionService.Subscription("test","https://fcm.googleapis.com/fcm/send/test",PushTestKeys.PUBLIC,PushTestKeys.AUTH),"{\"notification\":{\"title\":\"test\"}}".getBytes());
        assertThat(result).isEqualTo(201);
        var capture=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(capture.capture(),any(HttpResponse.BodyHandler.class));
        var request=capture.getValue();
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).startsWith("vapid ");
        assertThat(request.headers().firstValue("Content-Encoding").orElseThrow()).isEqualTo("aes128gcm");
        assertThat(request.timeout().orElseThrow().toSeconds()).isEqualTo(8);
        assertThat(request.bodyPublisher().orElseThrow().contentLength()).isGreaterThan(40);
    }
    @Test void rejectsLocalAndUntrustedEndpointsBeforeSending() {
        for(String endpoint:new String[]{"http://localhost/test","https://127.0.0.1/test","https://fcm.googleapis.com.attacker.test/a","https://fcm.googleapis.com:8443/a","https://user@fcm.googleapis.com/a"}) {
            assertThatThrownBy(()->PushSubscriptionService.validateEndpoint(endpoint)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }
}
