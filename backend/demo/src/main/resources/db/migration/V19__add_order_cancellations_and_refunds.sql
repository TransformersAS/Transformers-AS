-- Cancelaciones de pedido (D10): reutilizable por CU-11 (comprador) y CU-23 (vendedor). Una por pedido.
-- reason_code no lleva CHECK a propósito: CU-11 añadirá los motivos del comprador sin migrar este esquema.
CREATE TABLE order_cancellations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    initiator VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    details VARCHAR(1000) NULL,
    cancelled_by_id BIGINT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_cancellations_order UNIQUE (order_id),
    CONSTRAINT fk_order_cancellations_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_order_cancellations_initiator CHECK (initiator IN ('SELLER', 'BUYER'))
);

-- Reembolsos (D10, RNF-009, RNF-043). idempotency_key UNIQUE garantiza un solo reembolso por causa:
-- order-cancel-{orderId} para cancelaciones; CU-19 usará return-{returnId}.
CREATE TABLE refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    provider_reference VARCHAR(100) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_refunds_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_refunds_amount CHECK (amount > 0),
    CONSTRAINT chk_refunds_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_refunds_order ON refunds (order_id);
CREATE INDEX idx_refunds_status ON refunds (status, created_at);
