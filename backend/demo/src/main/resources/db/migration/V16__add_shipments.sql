-- Referencia logística de un pedido (RF-115). Un pedido tiene a lo sumo un envío (UNIQUE order_id):
-- la solicitud repetida reutiliza la referencia existente y no genera otro registro (A5/A6, RNF-043).
CREATE TABLE shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    provider_shipment_id VARCHAR(100) NOT NULL,
    tracking_code VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_shipments_order UNIQUE (order_id),
    CONSTRAINT uq_shipments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
