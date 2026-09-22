-- Solo se carga con el perfil demo. Identifica exactamente el pedido restaurable.
CREATE TABLE IF NOT EXISTS demo_fixture (
    fixture_key VARCHAR(40) PRIMARY KEY,
    order_id BIGINT NULL,
    CONSTRAINT fk_demo_fixture_order FOREIGN KEY (order_id) REFERENCES orders(id)
);
INSERT IGNORE INTO demo_fixture (fixture_key) VALUES ('cu11');
