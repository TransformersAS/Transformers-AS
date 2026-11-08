-- CU-26 Analizar ventas y rentabilidad.
-- Costo unitario interno del producto: lo registra el vendedor (CU-14) y nunca se muestra a los compradores.
ALTER TABLE products
    ADD COLUMN unit_cost DECIMAL(19, 2) NULL,
    ADD CONSTRAINT chk_products_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);

-- Copia del costo en el momento de la compra: la rentabilidad histórica no cambia si el vendedor corrige el costo
-- del producto después. Los pedidos anteriores a esta migración quedan sin costo (NULL) y la analítica lo informa.
ALTER TABLE order_items
    ADD COLUMN unit_cost DECIMAL(19, 2) NULL,
    ADD CONSTRAINT chk_order_items_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);

-- La analítica filtra los pedidos de una tienda por fecha (sin importar el estado) y agrupa sus líneas por producto.
CREATE INDEX idx_orders_store_created ON orders (store_id, created_at);
CREATE INDEX idx_order_items_product ON order_items (product_id);
