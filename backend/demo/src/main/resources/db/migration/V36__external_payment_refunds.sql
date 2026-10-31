-- No requiere pedido: un pago sin compra materializada también puede necesitar devolución.
CREATE TABLE external_payment_refunds (
    payment_id VARCHAR(100) PRIMARY KEY,
    idempotency_key VARCHAR(36) NOT NULL UNIQUE,
    amount DECIMAL(19,2) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'INTENT',
    legacy_refund_id BIGINT NULL,
    provider_refund_id VARCHAR(100) NULL,
    externally_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    submission_started BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(100) NULL,
    confirmation_source VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_external_refund_payment FOREIGN KEY (payment_id) REFERENCES external_payment_observations(payment_id),
    CONSTRAINT fk_external_refund_legacy FOREIGN KEY (legacy_refund_id) REFERENCES refunds(id),
    INDEX idx_external_refund_work (status,next_attempt_at)
);

CREATE TABLE external_refund_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id VARCHAR(100) NOT NULL,
    attempt_number INT NOT NULL,
    amount DECIMAL(19,2) NULL,
    phase VARCHAR(40) NOT NULL,
    result VARCHAR(40) NOT NULL,
    provider_refund_id VARCHAR(100) NULL,
    error VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_external_refund_event FOREIGN KEY (payment_id) REFERENCES external_payment_refunds(payment_id),
    INDEX idx_external_refund_event (payment_id,id)
);

ALTER TABLE payment_checkouts
    ADD COLUMN financial_discovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN financial_discovery_next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN financial_discovery_error VARCHAR(100) NULL;
