package com.transformersas.marketplace.notifications.infrastructure.queue;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.core.*;

@Configuration
@ConditionalOnProperty(name="notifications.queue.enabled",havingValue="true")
public class NotificationQueueConfiguration {
    public static final String QUEUE="marketplace.notifications";
    public static final String DEAD="marketplace.notifications.dead";
    public static final String EXCHANGE="marketplace.notifications.dlx";
    @Bean public Declarables notificationQueues() {
        return new Declarables(QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(DEAD).build(),
                new DirectExchange(EXCHANGE,true,false),new Queue(DEAD,true),
                new Binding(DEAD,Binding.DestinationType.QUEUE,EXCHANGE,DEAD,null));
    }
}
