-- RF-027: no backfill; NULL significa información histórica desconocida.
ALTER TABLE orders
    ADD COLUMN discount_amount DECIMAL(19, 2) NULL,
    ADD COLUMN shipping_cost DECIMAL(19, 2) NULL,
    ADD COLUMN seller_name_snapshot VARCHAR(255) NULL;
