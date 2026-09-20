-- Seguimiento logístico de devoluciones (CU-25). return_id es el identificador de la devolución que asigna CU-19;
-- todavía no existe tabla de devoluciones, por eso no lleva clave foránea: cuando CU-19 la cree podrá añadirla.
-- El comprador y la tienda se guardan aquí para que solo ellos consulten el seguimiento (RNF-010).
CREATE TABLE return_shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    buyer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    provider_return_id VARCHAR(100) NOT NULL,
    tracking_code VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    -- Recogidas fallidas acumuladas: al llegar a 3 no se piden más recogidas automáticas (A2).
    failed_pickups INT NOT NULL DEFAULT 0,
    pickup_stopped BOOLEAN NOT NULL DEFAULT FALSE,
    picked_up_at DATETIME(6) NULL,
    delivered_at DATETIME(6) NULL,
    tracking_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_polled_at DATETIME(6) NULL,
    poll_failures INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_return_shipments_return UNIQUE (return_id),
    CONSTRAINT uq_return_shipments_provider UNIQUE (provider_return_id),
    CONSTRAINT fk_return_shipments_buyer FOREIGN KEY (buyer_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_return_shipments_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT chk_return_shipments_status CHECK (status IN
        ('PICKUP_PENDING', 'PICKED_UP', 'IN_RETURN', 'LOGISTICS_ISSUE', 'PICKUP_FAILED', 'DELIVERED_TO_SELLER')),
    CONSTRAINT chk_return_shipments_failed CHECK (failed_pickups >= 0)
);

CREATE INDEX idx_return_shipments_poll ON return_shipments (tracking_active, last_polled_at);
CREATE INDEX idx_return_shipments_buyer ON return_shipments (buyer_account_id);
CREATE INDEX idx_return_shipments_store ON return_shipments (store_id);

-- Línea de tiempo de la devolución. La UNIQUE (devolución, id de evento) hace idempotente cada actualización (A4).
-- source SYSTEM es el registro inicial «Recogida pendiente» que crea el marketplace al registrar la devolución.
CREATE TABLE return_tracking_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_shipment_id BIGINT NOT NULL,
    provider_event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    source VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    description VARCHAR(500) NULL,
    location VARCHAR(200) NULL,
    evidence_type VARCHAR(32) NULL,
    evidence_reference VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_return_tracking_event UNIQUE (return_shipment_id, provider_event_id),
    CONSTRAINT fk_return_tracking_shipment FOREIGN KEY (return_shipment_id) REFERENCES return_shipments (id),
    CONSTRAINT chk_return_tracking_source CHECK (source IN ('WEBHOOK', 'POLLING', 'SYSTEM')),
    CONSTRAINT chk_return_tracking_outcome CHECK (outcome IN ('APPLIED', 'RECORDED', 'OUT_OF_ORDER'))
);

CREATE INDEX idx_return_tracking_timeline ON return_tracking_events (return_shipment_id, occurred_at, id);
