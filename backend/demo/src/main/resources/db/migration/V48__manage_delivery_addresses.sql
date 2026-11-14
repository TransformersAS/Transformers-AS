ALTER TABLE addresses
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN default_account_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN is_default = TRUE AND deleted = FALSE THEN account_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_address_default_account (default_account_id),
    ADD INDEX idx_address_owner_active (account_id, deleted, id);

-- Only known owners receive a default. Legacy orphan addresses remain unassigned.
UPDATE addresses a
JOIN (SELECT account_id, MIN(id) id FROM addresses WHERE account_id IS NOT NULL GROUP BY account_id) first_address
    ON first_address.id = a.id
SET a.is_default = TRUE;

CREATE INDEX idx_orders_address_status ON orders(address_id,status);
