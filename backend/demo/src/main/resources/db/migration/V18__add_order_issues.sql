-- Novedades de preparación de un pedido (RF-121, A3). Un pedido con novedades abiertas no puede pasar a
-- Listo para despacho hasta resolverlas o cancelar el pedido.
CREATE TABLE order_issues (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reported_by_type VARCHAR(16) NOT NULL,
    reported_by_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    resolved_by_id BIGINT NULL,
    -- A lo sumo una inconsistencia de inventario ABIERTA por pedido: la detección automática es idempotente
    -- aunque dos peticiones simultáneas la detecten a la vez. NULL (varias filas permitidas) en los demás casos.
    open_inventory_issue_order_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'OPEN' AND type = 'INVENTORY_INCONSISTENCY' THEN order_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_issues_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_order_issues_open_inventory UNIQUE (open_inventory_issue_order_id),
    CONSTRAINT chk_order_issues_type CHECK (type IN ('INVENTORY_INCONSISTENCY', 'DAMAGED_PRODUCT', 'OTHER')),
    CONSTRAINT chk_order_issues_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_order_issues_reporter CHECK (reported_by_type IN ('SELLER', 'BUYER', 'SYSTEM', 'LOGISTICS'))
);

CREATE INDEX idx_order_issues_order_status ON order_issues (order_id, status);
