-- Historial de estados de pedido (D6). Se escribe en la misma transacción que el cambio.
CREATE TABLE order_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- Sin ON DELETE CASCADE: el historial no debe desaparecer con el pedido.
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_order_status_history_actor CHECK (actor_type IN ('SELLER', 'BUYER', 'SYSTEM', 'LOGISTICS'))
);

CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, id);

-- Backfill explícito: un registro inicial por pedido existente, marcado como migración.
INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, correlation_id, created_at)
SELECT id, NULL, status, 'SYSTEM', NULL, 'Registro inicial de migración', 'migration-V14', created_at
FROM orders;
