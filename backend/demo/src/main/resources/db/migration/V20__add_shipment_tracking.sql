-- Seguimiento logístico de pedidos (CU-24). El estado vigente vive en orders.status; aquí queda la línea de
-- tiempo con cada actualización recibida del servicio logístico (webhook o consulta) y el control de consulta.

-- tracking_active: mientras sea TRUE el marketplace vuelve a consultar el envío (A7); pasa a FALSE al llegar a un
-- estado final (Entregado o Retornado al vendedor). Los envíos anteriores a esta migración siguen activos.
ALTER TABLE shipments
    ADD COLUMN tracking_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN last_polled_at DATETIME(6) NULL,
    ADD COLUMN poll_failures INT NOT NULL DEFAULT 0;

CREATE INDEX idx_shipments_tracking_poll ON shipments (tracking_active, last_polled_at);

-- Cada actualización se identifica por (envío, id de evento del proveedor): la UNIQUE hace idempotente el
-- procesamiento (A5, RNF-043) aunque el mismo evento llegue por webhook y por consulta a la vez.
-- outcome: APPLIED movió el estado del pedido; RECORDED es informativo; OUT_OF_ORDER se conserva solo para
-- trazabilidad porque retrocedería el estado (A6). Sin ON DELETE CASCADE: la línea de tiempo no debe desaparecer.
CREATE TABLE shipment_tracking_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
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
    CONSTRAINT uq_shipment_tracking_event UNIQUE (shipment_id, provider_event_id),
    CONSTRAINT fk_shipment_tracking_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id),
    CONSTRAINT fk_shipment_tracking_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_shipment_tracking_source CHECK (source IN ('WEBHOOK', 'POLLING')),
    CONSTRAINT chk_shipment_tracking_outcome CHECK (outcome IN ('APPLIED', 'RECORDED', 'OUT_OF_ORDER'))
);

CREATE INDEX idx_shipment_tracking_timeline ON shipment_tracking_events (shipment_id, occurred_at, id);
CREATE INDEX idx_shipment_tracking_order ON shipment_tracking_events (order_id);
