package com.transformersas.marketplace.notifications.infrastructure.push;

import org.springframework.stereotype.Component;
import java.net.http.*;
import java.time.Duration;
import java.security.Security;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

@Component
public class WebPushTransport implements BrowserPushTransport {
    private final PushSettings settings;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public WebPushTransport(PushSettings settings) {
        this(settings,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    public WebPushTransport(PushSettings settings,HttpClient client) {
        this.client=client;
        this.settings=settings;
        if(Security.getProvider("BC")==null) Security.addProvider(new BouncyCastleProvider());
    }
    @Override public int send(PushSubscriptionService.Subscription subscription,byte[] payload) throws Exception {
        PushSubscriptionService.validateEndpoint(subscription.endpoint());
        var encrypted=new Encryption(settings).prepare(subscription,payload);
        var request=HttpRequest.newBuilder(PushSubscriptionService.validateEndpoint(encrypted.getUrl())).timeout(Duration.ofSeconds(8));
        encrypted.getHeaders().forEach(request::header);
        byte[] body=encrypted.getBody();
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    private static final class Encryption extends AbstractPushService<Encryption> {
        Encryption(PushSettings settings) throws java.security.GeneralSecurityException {super(settings.publicKey(),settings.privateKey(),settings.subject());}
        nl.martijndwars.webpush.HttpRequest prepare(PushSubscriptionService.Subscription subscription,byte[] payload) throws Exception {
            return prepareRequest(new nl.martijndwars.webpush.Notification(subscription.endpoint(),subscription.p256dh(),subscription.auth(),payload,300),Encoding.AES128GCM);
        }
    }
}
