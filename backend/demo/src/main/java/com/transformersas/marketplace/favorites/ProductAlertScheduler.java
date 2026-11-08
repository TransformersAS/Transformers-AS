package com.transformersas.marketplace.favorites;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="favorites.alerts.enabled",havingValue="true",matchIfMissing=true)
public class ProductAlertScheduler {
    private final JdbcTemplate jdbc;
    private final ProductAlertService service;
    private String cursor="";
    public ProductAlertScheduler(JdbcTemplate jdbc,ProductAlertService service) { this.jdbc=jdbc;this.service=service; }
    @Scheduled(fixedDelayString="${favorites.alerts.interval:1000}",initialDelayString="${favorites.alerts.initial-delay:1000}")
    public void tick() {
        // Bounded batches and a cursor prevent a permanently failing row from starving other alerts.
        var ids=jdbc.queryForList("SELECT id FROM product_alerts WHERE status='ACTIVE' AND id>? ORDER BY id LIMIT 100",String.class,cursor);
        for (String id:ids) {
            try { service.evaluate(id); }
            catch (RuntimeException failed) { LoggerFactory.getLogger(getClass()).warn("No se pudo evaluar alerta {}",id); }
            cursor=id;
        }
        if (ids.size()<100) cursor="";
    }
}
