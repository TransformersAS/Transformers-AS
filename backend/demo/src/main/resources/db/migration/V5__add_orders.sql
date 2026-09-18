CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    status VARCHAR(20) NOT NULL,

    total DECIMAL(19, 2) NOT NULL,

    address_id BIGINT NOT NULL,

    shipping_method VARCHAR(30) NOT NULL,

    transaction_id VARCHAR(100) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT uq_orders_transaction_id
        UNIQUE (transaction_id),

    CONSTRAINT fk_orders_address
        FOREIGN KEY (address_id)
        REFERENCES addresses(id)
);


CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    order_id BIGINT NOT NULL,

    product_id BIGINT NOT NULL,

    product_name VARCHAR(255) NOT NULL,

    quantity INT NOT NULL,

    unit_price DECIMAL(19, 2) NOT NULL,

    subtotal DECIMAL(19, 2) NOT NULL,

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
        ON DELETE CASCADE
);


CREATE INDEX idx_order_items_order
    ON order_items(order_id);