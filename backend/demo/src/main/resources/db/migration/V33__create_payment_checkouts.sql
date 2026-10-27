CREATE TABLE payment_checkouts (
    id VARCHAR(36) PRIMARY KEY,
    account_id BIGINT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    total DECIMAL(19,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    preference_id VARCHAR(150),
    checkout_url VARCHAR(2048),
    payment_id VARCHAR(100),
    order_id BIGINT,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_checkout_payment (payment_id),
    INDEX idx_checkout_account (account_id, created_at),
    CONSTRAINT fk_checkout_account FOREIGN KEY (account_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_checkout_order FOREIGN KEY (order_id) REFERENCES orders(id)
);
