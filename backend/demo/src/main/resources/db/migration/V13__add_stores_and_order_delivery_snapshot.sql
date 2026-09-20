-- CU-23 F1. Continúa desde V12 (main ya usa V8–V12 para moderación, recuperación de contraseña y comprador del pedido).
-- Ningún dato existente se borra: los datos previos se completan de forma explícita.

-- Tienda mínima. CU-18 la ampliará; por ahora solo identifica al dueño del pedido y del producto.
CREATE TABLE stores (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

INSERT INTO stores (id, name) VALUES (1, 'Tienda principal');

-- Backfill explícito: todo producto existente pertenece a la tienda 1.
-- El DEFAULT 1 se mantiene en products hasta que CU-14/CU-18 asignen la tienda al crear productos.
ALTER TABLE products
    ADD COLUMN store_id BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT fk_products_store FOREIGN KEY (store_id) REFERENCES stores (id);

-- Pedidos: tienda, estado de pago y snapshot de entrega. El comprador es orders.account_id (V11); el ancho de status ya lo amplió V12.
ALTER TABLE orders
    ADD COLUMN store_id BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN payment_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN delivery_recipient_name VARCHAR(255) NULL,
    ADD COLUMN delivery_street VARCHAR(255) NULL,
    ADD COLUMN delivery_city VARCHAR(255) NULL,
    ADD COLUMN delivery_department VARCHAR(255) NULL,
    ADD COLUMN delivery_postal_code VARCHAR(50) NULL,
    ADD COLUMN delivery_phone VARCHAR(50) NULL;

-- Backfill del snapshot desde la dirección vigente al momento de esta migración.
UPDATE orders o
    JOIN addresses a ON a.id = o.address_id
SET o.delivery_recipient_name = a.recipient_name,
    o.delivery_street = a.street,
    o.delivery_city = a.city,
    o.delivery_department = a.department,
    o.delivery_postal_code = a.postal_code,
    o.delivery_phone = a.phone;

-- Si algún pedido quedara sin snapshot esta sentencia falla: nunca se inventan datos.
ALTER TABLE orders
    MODIFY COLUMN delivery_recipient_name VARCHAR(255) NOT NULL,
    MODIFY COLUMN delivery_street VARCHAR(255) NOT NULL,
    MODIFY COLUMN delivery_city VARCHAR(255) NOT NULL,
    MODIFY COLUMN delivery_department VARCHAR(255) NOT NULL,
    MODIFY COLUMN delivery_phone VARCHAR(50) NOT NULL,
    ALTER COLUMN store_id DROP DEFAULT,
    ADD CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores (id),
    ADD CONSTRAINT chk_orders_status CHECK (status IN (
        'CONFIRMED', 'IN_PREPARATION', 'READY_FOR_DISPATCH', 'PICKED_UP', 'IN_TRANSIT',
        'DELIVERED', 'DELIVERY_EXCEPTION', 'DELIVERY_ATTEMPT_FAILED', 'RETURNED_TO_SELLER', 'CANCELLED',
        'CANCELLATION_REQUESTED')),
    ADD CONSTRAINT chk_orders_payment_status CHECK (payment_status IN ('APPROVED', 'REFUND_PENDING', 'REFUNDED'));

CREATE INDEX idx_orders_store_status_created ON orders (store_id, status, created_at);
