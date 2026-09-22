CREATE TABLE email_verification_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_email_verification_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_account FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
    INDEX idx_email_verification_account (account_id)
);

-- Sessions issued before CU-08 enforced email verification must not preserve that bypass.
DELETE s FROM SPRING_SESSION s
JOIN user_accounts a ON a.email = s.PRINCIPAL_NAME
WHERE a.email_verified_at IS NULL;
