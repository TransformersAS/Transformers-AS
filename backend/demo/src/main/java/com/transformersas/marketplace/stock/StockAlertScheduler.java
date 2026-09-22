package com.transformersas.marketplace.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Revisa cada pocos minutos el stock de los productos con nivel mínimo (CU-15). Las compras descuentan el stock sin
 * pasar por el servicio de inventario, así que sin esta revisión el aviso solo saldría al registrar un movimiento.
 * Las pruebas la apagan con {@code inventory.alerts.scheduling-enabled=false} y llaman al servicio directamente.
 */
@Component
@ConditionalOnProperty(name = "inventory.alerts.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class StockAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockAlertScheduler.class);

    private final SellerInventoryService inventory;

    StockAlertScheduler(SellerInventoryService inventory) {
        this.inventory = inventory;
    }

    @Scheduled(fixedDelayString = "${inventory.alerts.check-interval:PT5M}")
    void checkLowStock() {
        try {
            int changed = inventory.checkAlerts();
            if (changed > 0) {
                log.info("{} productos cambiaron de estado de alerta de stock.", changed);
            }
        } catch (RuntimeException failure) {
            log.error("Falló la revisión de alertas de stock.", failure);
        }
    }
}
