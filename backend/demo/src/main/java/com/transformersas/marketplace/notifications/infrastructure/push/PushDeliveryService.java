package com.transformersas.marketplace.notifications.infrastructure.push;

import com.transformersas.marketplace.notifications.domain.model.Notification;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Service
public class PushDeliveryService {
    private final PushSettings settings;private final PushSubscriptionService subscriptions;
    private final BrowserPushTransport transport;private final JdbcTemplate jdbc;private final ObjectMapper json;
    public PushDeliveryService(PushSettings settings,PushSubscriptionService subscriptions,BrowserPushTransport transport,JdbcTemplate jdbc,ObjectMapper json) {
        this.settings=settings;this.subscriptions=subscriptions;this.transport=transport;this.jdbc=jdbc;this.json=json;
    }
    public boolean deliver(Notification notification) {
        if(!settings.enabled()) return true;
        if(!settings.ready()) throw new IllegalStateException("Push no configurado");
        boolean success=true;
        for(var subscription:subscriptions.recipients(notification.recipientType().name(),notification.recipientId())) {
            jdbc.update("INSERT INTO push_deliveries(notification_id,subscription_id) VALUES (?,?) ON DUPLICATE KEY UPDATE notification_id=VALUES(notification_id)",notification.id(),subscription.id());
            String state=jdbc.queryForObject("SELECT status FROM push_deliveries WHERE notification_id=? AND subscription_id=?",String.class,notification.id(),subscription.id());
            if("SENT".equals(state)) continue;
            try {
                // Generic content protects shared devices and lock screens; details require an authenticated inbox.
                byte[] payload=json.writeValueAsBytes(Map.of("notification",Map.of("title","Marketplace", "body","Tienes una nueva notificación. Abre la aplicación para consultarla.",
                    "tag","marketplace-notification-"+notification.id(),"renotify",false,
                    "data",Map.of("onActionClick",Map.of("default",Map.of("operation","openWindow","url","/?notificaciones=1"))))));
                int status=transport.send(subscription,payload);
                if(status==404 || status==410) {subscriptions.expire(subscription.id());continue;}
                boolean accepted=status>=200 && status<300;
                jdbc.update("UPDATE push_deliveries SET status=?,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6) WHERE notification_id=? AND subscription_id=?",accepted?"SENT":"FAILED",notification.id(),subscription.id());
                if(!accepted) success=false;
            } catch(Exception failed) {
                if(failed instanceof InterruptedException) Thread.currentThread().interrupt();
                jdbc.update("UPDATE push_deliveries SET status='FAILED',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6) WHERE notification_id=? AND subscription_id=?",notification.id(),subscription.id());
                success=false;
            }
        }
        return success;
    }
}
