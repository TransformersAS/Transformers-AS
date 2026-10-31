-- No se reconstruye procedencia a partir de fechas ni valores comerciales.
-- UNKNOWN también protege inserciones realizadas por versiones anteriores.
ALTER TABLE orders
    ADD COLUMN delivery_source VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN seller_source VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN payment_source VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN payment_status_at_capture VARCHAR(20) NULL,
    ADD CONSTRAINT chk_order_delivery_source CHECK (delivery_source IN ('UNKNOWN','MIGRATED','CAPTURED_AT_PURCHASE')),
    ADD CONSTRAINT chk_order_seller_source CHECK (seller_source IN ('UNKNOWN','MIGRATED','CAPTURED_AT_PURCHASE')),
    ADD CONSTRAINT chk_order_payment_source CHECK (payment_source IN ('UNKNOWN','MIGRATED','CAPTURED_AT_PURCHASE'));
