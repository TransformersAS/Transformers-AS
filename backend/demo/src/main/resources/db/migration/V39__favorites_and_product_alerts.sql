CREATE TABLE buyer_favorites (
 account_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(account_id, product_id),
 FOREIGN KEY(account_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
 FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE
);
CREATE TABLE product_alerts (
 id VARCHAR(36) PRIMARY KEY,
 account_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 variant_name VARCHAR(255),
 target_price DECIMAL(19,2),
 status VARCHAR(20) NOT NULL,
 revision INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(account_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
 FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
 INDEX idx_alert_evaluation(status,id),
 INDEX idx_alert_owner(account_id,created_at)
);
