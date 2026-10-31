-- Solo referencias verificadas; las filas anteriores no se rellenan con estados financieros supuestos.
CREATE TABLE external_payment_observations (
    payment_id VARCHAR(100) PRIMARY KEY,
    checkout_id VARCHAR(36) NOT NULL,
    verified_status VARCHAR(40) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    collector_id VARCHAR(100) NOT NULL,
    verification_count INT NOT NULL DEFAULT 1,
    approved_seen BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_status VARCHAR(40) NOT NULL,
    order_id BIGINT NULL,
    first_verified_at DATETIME(6) NOT NULL,
    last_verified_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    CONSTRAINT fk_external_payment_checkout FOREIGN KEY (checkout_id) REFERENCES payment_checkouts(id),
    CONSTRAINT fk_external_payment_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_external_payment_checkout (checkout_id, resolution_status)
);

ALTER TABLE payment_checkouts
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN recovery_lease_token VARCHAR(36) NULL,
    ADD COLUMN recovery_lease_until DATETIME(6) NULL,
    ADD COLUMN recovery_offset INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_result VARCHAR(50) NULL,
    ADD COLUMN recovery_last_error VARCHAR(500) NULL,
    ADD INDEX idx_checkout_recovery (status, recovery_next_at);

CREATE TABLE payment_recovery_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    checkout_id VARCHAR(36) NOT NULL,
    lease_token VARCHAR(36) NOT NULL UNIQUE,
    attempt_number INT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    result VARCHAR(50) NULL,
    error VARCHAR(500) NULL,
    CONSTRAINT fk_payment_recovery_checkout FOREIGN KEY (checkout_id) REFERENCES payment_checkouts(id),
    INDEX idx_payment_recovery_checkout (checkout_id, id)
);
