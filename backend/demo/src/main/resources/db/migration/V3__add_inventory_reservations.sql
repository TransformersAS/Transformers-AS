CREATE TABLE inventory_reservations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    product_id BIGINT NOT NULL,

    quantity INT NOT NULL,

    status VARCHAR(20) NOT NULL,

    created_at DATETIME NOT NULL,

    expires_at DATETIME NOT NULL,

    CONSTRAINT fk_inventory_reservation_product
        FOREIGN KEY (product_id)
        REFERENCES products(id)
);

CREATE INDEX idx_inventory_reservation_product
    ON inventory_reservations(product_id);

CREATE INDEX idx_inventory_reservation_status
    ON inventory_reservations(status);

CREATE INDEX idx_inventory_reservation_expires
    ON inventory_reservations(expires_at);