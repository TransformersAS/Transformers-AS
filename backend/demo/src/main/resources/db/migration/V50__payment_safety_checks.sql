-- Evidencia de comprobaciones realmente ejecutadas. No se rellenan pagos históricos.
CREATE TABLE payment_safety_checks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    verification_id VARCHAR(36) NOT NULL,
    checkout_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(100) NOT NULL,
    phase VARCHAR(10) NOT NULL,
    result VARCHAR(20) NOT NULL,
    expected_amount DECIMAL(19,2) NOT NULL,
    observed_amount DECIMAL(19,2) NOT NULL,
    expected_currency VARCHAR(3) NOT NULL,
    observed_currency VARCHAR(3) NOT NULL,
    expected_collector VARCHAR(100) NOT NULL,
    observed_collector VARCHAR(100) NOT NULL,
    provider_status VARCHAR(40) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_safety_checkout FOREIGN KEY (checkout_id) REFERENCES payment_checkouts(id) ON DELETE CASCADE,
    CONSTRAINT uq_safety_verification_phase UNIQUE (verification_id, phase),
    INDEX idx_safety_checkout (checkout_id, id)
);
