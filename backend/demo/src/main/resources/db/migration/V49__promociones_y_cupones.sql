-- CU-16: diseñar promociones y cupones de venta.
-- El vendedor crea cupones para su tienda: un código que el comprador escribe en el checkout, un descuento
-- (porcentaje o monto fijo), una compra mínima opcional, una vigencia y, si quiere, los productos a los que aplica.
-- El checkout (CU-03) no se modifica: un aspecto del paquete promotions completa su vista previa cuando el código
-- no es DESC10. Esta versión no controla límites de uso.
CREATE TABLE promotions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    -- Se guarda en mayúsculas; el comprador puede escribirlo como quiera.
    code VARCHAR(30) NOT NULL,
    -- PERCENT: discount_value es un porcentaje (1 a 100). AMOUNT: es un monto en pesos.
    discount_type VARCHAR(10) NOT NULL,
    discount_value DECIMAL(19, 2) NOT NULL,
    -- Compra mínima sobre los productos a los que aplica el cupón; NULL = sin mínimo.
    min_purchase DECIMAL(19, 2) NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    -- Un cupón nace inactivo: el vendedor lo activa cuando lo ha revisado.
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_promotions_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT uk_promotions_store_code UNIQUE (store_id, code),
    CONSTRAINT chk_promotions_discount_type CHECK (discount_type IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT chk_promotions_discount_value CHECK (discount_value > 0),
    CONSTRAINT chk_promotions_min_purchase CHECK (min_purchase IS NULL OR min_purchase > 0),
    CONSTRAINT chk_promotions_dates CHECK (ends_at > starts_at)
);

-- Productos a los que aplica el cupón. Sin filas, aplica a todos los productos de la tienda.
CREATE TABLE promotion_products (
    promotion_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (promotion_id, product_id),
    CONSTRAINT fk_promotion_products_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_promotion_products_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);
