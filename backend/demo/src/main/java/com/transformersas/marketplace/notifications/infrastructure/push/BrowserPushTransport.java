package com.transformersas.marketplace.notifications.infrastructure.push;

/** Returns the push provider's HTTP status; never logs endpoints or encryption keys. */
public interface BrowserPushTransport {
    int send(PushSubscriptionService.Subscription subscription,byte[] payload) throws Exception;
}
