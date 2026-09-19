package com.transformersas.marketplace.reports.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ModerationConfiguration {

    /** Reloj inyectable: los plazos de 72 h se prueban moviéndolo en lugar de esperar. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
