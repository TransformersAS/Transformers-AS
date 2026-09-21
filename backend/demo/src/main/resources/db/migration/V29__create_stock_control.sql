-- CU-15: controlar el inventario y el reabastecimiento.
-- El stock sigue viviendo en products.stock (el vendedor lo indica al publicar y las compras lo descuentan).
-- Aquí se añade el nivel mínimo por producto y el historial de los movimientos manuales del vendedor.
-- Primera entrega: no incluye las cargas masivas con plantillas de Excel (quedan para la segunda entrega).
ALTER TABLE products
    -- 0 significa "sin aviso". Cuando el stock baja o iguala este nivel, la tienda recibe una alerta.
    ADD COLUMN min_stock INT NOT NULL DEFAULT 0,
    -- Recuerda si ya se avisó de stock bajo, para no repetir la alerta hasta que se reponga.
    ADD COLUMN low_stock_alerted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_products_min_stock_nonnegative CHECK (min_stock >= 0);

CREATE TABLE stock_movements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    -- ENTRY: llegó mercancía. ADJUSTMENT: el vendedor fijó el conteo real.
    type VARCHAR(12) NOT NULL,
    -- Unidades que sumó o restó el movimiento (en un ajuste puede ser negativa).
    quantity INT NOT NULL,
    stock_after INT NOT NULL,
    reason VARCHAR(255) NULL,
    actor_account_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_movements_actor FOREIGN KEY (actor_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_stock_movements_type CHECK (type IN ('ENTRY', 'ADJUSTMENT')),
    CONSTRAINT chk_stock_movements_stock_after CHECK (stock_after >= 0)
);

CREATE INDEX idx_stock_movements_product ON stock_movements (product_id, created_at);
