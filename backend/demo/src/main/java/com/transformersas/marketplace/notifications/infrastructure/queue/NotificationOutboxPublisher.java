package com.transformersas.marketplace.notifications.infrastructure.queue;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;

/** Rows are persisted by JdbcNotificationRepository in the business transaction. */
@Component
@ConditionalOnProperty(name="notifications.queue.enabled",havingValue="true")
public class NotificationOutboxPublisher {
    private final JdbcTemplate jdbc;private final RabbitTemplate rabbit;private final TransactionTemplate tx;
    public NotificationOutboxPublisher(JdbcTemplate jdbc,RabbitTemplate rabbit,TransactionTemplate tx) {this.jdbc=jdbc;this.rabbit=rabbit;this.tx=tx;}
    @Scheduled(fixedDelayString="${notifications.queue.interval-ms:1000}",initialDelayString="${notifications.queue.initial-delay-ms:1000}")
    public void publishPending() {
        for(int i=0;i<20;i++) {
            try {
                Boolean published=tx.execute(status->{
                    var ids=jdbc.queryForList("""
                        SELECT notification_id FROM notification_jobs
                        WHERE status IN ('PENDING','PUBLISHED','RETRY','PROCESSING') AND available_at<=CURRENT_TIMESTAMP(6)
                          AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(6))
                        ORDER BY available_at,notification_id LIMIT 1 FOR UPDATE SKIP LOCKED
                        """,Long.class);
                    if(ids.isEmpty()) return false;
                    Long id=ids.getFirst();
                    // Also declare here: recovery works when the broker returns after startup.
                    rabbit.execute(channel->{channel.queueDeclare(NotificationQueueConfiguration.DEAD,true,false,false,null);
                        channel.exchangeDeclare(NotificationQueueConfiguration.EXCHANGE,"direct",true);
                        channel.queueBind(NotificationQueueConfiguration.DEAD,NotificationQueueConfiguration.EXCHANGE,NotificationQueueConfiguration.DEAD);
                        channel.queueDeclare(NotificationQueueConfiguration.QUEUE,true,false,false,java.util.Map.of("x-dead-letter-exchange",NotificationQueueConfiguration.EXCHANGE,"x-dead-letter-routing-key",NotificationQueueConfiguration.DEAD));return null;});
                    rabbit.invoke(operations->{
                        var message=MessageBuilder.withBody(id.toString().getBytes(StandardCharsets.UTF_8)).setDeliveryMode(MessageDeliveryMode.PERSISTENT).setContentType("text/plain").build();
                        operations.send("",NotificationQueueConfiguration.QUEUE,message);
                        operations.waitForConfirmsOrDie(3000);return null;
                    });
                    jdbc.update("UPDATE notification_jobs SET status='PUBLISHED',available_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE notification_id=?",id);
                    return true;
                });
                if(!Boolean.TRUE.equals(published)) break;
            } catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cola no disponible: el evento permanece en la outbox");break;
            }
        }
    }
}
