package com.transformersas.marketplace.notifications.infrastructure.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PushSettings(
        @Value("${notifications.push.enabled:false}") boolean enabled,
        @Value("${notifications.queue.enabled:false}") boolean queueEnabled,
        @Value("${notifications.push.public-key:}") String publicKey,
        @Value("${notifications.push.private-key:}") String privateKey,
        @Value("${notifications.push.subject:mailto:admin@example.com}") String subject) {
    public boolean ready() { return enabled && queueEnabled && !publicKey.isBlank() && !privateKey.isBlank(); }
    @Override public String toString() { return "PushSettings[enabled="+enabled+", configured="+ready()+"]"; }
}
