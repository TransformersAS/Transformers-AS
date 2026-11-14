package com.transformersas.marketplace.notifications.infrastructure.queue;

import com.transformersas.marketplace.notifications.application.usecase.DispatchExternalNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.ExternalStatus;
import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;
import com.transformersas.marketplace.notifications.infrastructure.push.PushDeliveryService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name="notifications.queue.enabled",havingValue="true")
public class NotificationQueueConsumer {
    private final JdbcTemplate jdbc;private final NotificationRepository notifications;
    private final DispatchExternalNotificationUseCase external;private final PushDeliveryService push;
    private final int maxAttempts;
    public NotificationQueueConsumer(JdbcTemplate jdbc,NotificationRepository notifications,DispatchExternalNotificationUseCase external,
            PushDeliveryService push,@Value("${notifications.queue.max-attempts:5}") int maxAttempts) {
        this.jdbc=jdbc;this.notifications=notifications;this.external=external;this.push=push;this.maxAttempts=maxAttempts;
        if(maxAttempts<1 || maxAttempts>10) throw new IllegalArgumentException("Intentos de cola entre 1 y 10");
    }
    @RabbitListener(queues=NotificationQueueConfiguration.QUEUE,concurrency="2",autoStartup="${notifications.queue.consumer-enabled:true}")
    public void onMessage(org.springframework.amqp.core.Message message) {receive(message.getBody());}
    public void receive(byte[] body) {
        String raw=new String(body,StandardCharsets.UTF_8);
        if(!raw.matches("[1-9][0-9]{0,17}")) throw new AmqpRejectAndDontRequeueException("Mensaje inválido");
        long id=Long.parseLong(raw);
        int claimed=jdbc.update("""
            UPDATE notification_jobs SET status='PROCESSING',attempts=attempts+1,
              locked_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6)
            WHERE notification_id=? AND status NOT IN ('DONE','FAILED')
              AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(6))
              AND (status<>'RETRY' OR available_at<=CURRENT_TIMESTAMP(6))
            """,id);
        if(claimed==0) return;
        boolean done=false;
        try {
            var notice=notifications.findById(id).orElse(null);
            if(notice==null) return;
            external.execute(id);
            boolean pushDone=push.deliver(notice);
            done=pushDone && notifications.findById(id).orElseThrow().externalStatus()==ExternalStatus.SENT;
        } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Envío pendiente para notificationId={}",id);
        }
        int attempts=jdbc.queryForObject("SELECT attempts FROM notification_jobs WHERE notification_id=?",Integer.class,id);
        String state=done?"DONE":attempts>=maxAttempts?"FAILED":"RETRY";
        jdbc.update("""
            UPDATE notification_jobs SET status=?,locked_until=NULL,available_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),
              last_error=?,updated_at=CURRENT_TIMESTAMP(6) WHERE notification_id=?
            """,state,attempts*10,done?null:"Fallo de envío; revisar proveedor o suscripción",id);
        if(state.equals("FAILED")) throw new AmqpRejectAndDontRequeueException("Intentos agotados para notificationId="+id);
    }
}
