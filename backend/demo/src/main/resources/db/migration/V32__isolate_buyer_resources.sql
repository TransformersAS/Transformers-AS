-- Legacy global data has no trustworthy owner. Keep it for historical references,
-- but never expose or adopt it through account-scoped buyer operations.
ALTER TABLE carts
    ADD COLUMN account_id BIGINT NULL,
    ADD CONSTRAINT fk_carts_account FOREIGN KEY (account_id) REFERENCES user_accounts(id),
    ADD CONSTRAINT uq_carts_account UNIQUE (account_id);

ALTER TABLE addresses
    ADD COLUMN account_id BIGINT NULL,
    ADD CONSTRAINT fk_addresses_account FOREIGN KEY (account_id) REFERENCES user_accounts(id),
    ADD INDEX idx_addresses_account (account_id);

ALTER TABLE inventory_reservations
    ADD COLUMN account_id BIGINT NULL,
    ADD CONSTRAINT fk_reservations_account FOREIGN KEY (account_id) REFERENCES user_accounts(id),
    ADD INDEX idx_reservations_account (account_id);
