ALTER TABLE orders
    ADD COLUMN account_id BIGINT NULL,
    ADD CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES user_accounts(id),
    ADD INDEX idx_orders_account (account_id);
