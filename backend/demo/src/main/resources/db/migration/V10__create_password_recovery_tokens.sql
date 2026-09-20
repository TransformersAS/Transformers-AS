CREATE TABLE password_recovery_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_password_recovery_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_recovery_account FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
    INDEX idx_password_recovery_account (account_id)
);
