package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.returns.domain.port.PickupStatusReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lee return_shipments.pickup_stopped (CU-25) sin modificar nada de logística. */
@Repository
class JdbcPickupStatusReader implements PickupStatusReader {
    private final JdbcClient jdbc;

    JdbcPickupStatusReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pickupBlocked(Long returnId) {
        return jdbc.sql("SELECT pickup_stopped FROM return_shipments WHERE return_id = ?").param(returnId)
                .query(Boolean.class).optional().orElse(false);
    }
}
