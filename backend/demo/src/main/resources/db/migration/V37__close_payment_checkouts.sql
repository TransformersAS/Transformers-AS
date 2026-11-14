-- Cierre voluntario irreversible, independiente de la resolución financiera.
ALTER TABLE payment_checkouts ADD COLUMN cancelled_at DATETIME(6) NULL;
