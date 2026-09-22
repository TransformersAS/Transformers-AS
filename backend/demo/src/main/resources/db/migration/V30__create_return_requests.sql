-- CU-19: devolución de compra. Una solicitud por línea de pedido (order_item_id UNIQUE): eso hace idempotente la
-- solicitud repetida (A3) e impide pedir de nuevo una línea Rechazada, que se disputa por reclamación (CU-13).
-- El id de esta tabla es el "return_id" con el que CU-25 (return_shipments) sigue el envío de retorno; no hay clave
-- foránea desde return_shipments para no acoplar los dos esquemas.
CREATE TABLE return_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    -- Copia de la línea al solicitar: la devolución no depende de que el pedido no vuelva a cambiar.
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(19, 2) NOT NULL,
    -- unit_price x quantity de la línea, sin envío (D4). Es lo que se reembolsa con la clave return-{id}.
    refund_amount DECIMAL(19, 2) NOT NULL,
    buyer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    -- BUYER: la pidió el comprador y se le aplica el plazo. CLAIM: nace Aprobada desde una reclamación (CU-13) y no se
    -- le aplica el plazo; origin_claim_id referencia esa reclamación sin clave foránea.
    origin VARCHAR(8) NOT NULL DEFAULT 'BUYER',
    origin_claim_id BIGINT NULL,
    -- Copia del plazo de la tienda y de la fecha de entrega con los que se evaluó la elegibilidad (D6). NULL en CLAIM.
    return_window_days INT NULL,
    delivered_at DATETIME(6) NULL,
    decision_note VARCHAR(1000) NULL,
    decided_by_account_id BIGINT NULL,
    decided_at DATETIME(6) NULL,
    return_method_code VARCHAR(64) NULL,
    method_chosen_at DATETIME(6) NULL,
    inspection_started_at DATETIME(6) NULL,
    inspection_due_at DATETIME(6) NULL,
    -- Problema reportado por el vendedor en la inspección (A8, RF-053): el estado sigue En inspección. claim_id es la
    -- reclamación abierta a nombre del comprador (CU-13), sin clave foránea.
    problem_reported BOOLEAN NOT NULL DEFAULT FALSE,
    problem_description VARCHAR(1000) NULL,
    problem_reported_at DATETIME(6) NULL,
    claim_id BIGINT NULL,
    refund_attempts INT NOT NULL DEFAULT 0,
    -- Próxima acción del barrido: fin de la ventana de inspección (IN_INSPECTION) o próximo reintento del reembolso
    -- (REFUND_PENDING). NULL en los demás estados.
    next_action_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_return_requests_order_item UNIQUE (order_item_id),
    CONSTRAINT fk_return_requests_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_return_requests_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_return_requests_buyer FOREIGN KEY (buyer_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_return_requests_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT chk_return_requests_status CHECK (status IN ('REQUESTED', 'IN_REVIEW', 'INFO_REQUIRED', 'REJECTED',
        'APPROVED', 'IN_INSPECTION', 'REFUND_PENDING', 'FINISHED')),
    CONSTRAINT chk_return_requests_origin CHECK (origin IN ('BUYER', 'CLAIM')
        AND ((origin = 'CLAIM') = (origin_claim_id IS NOT NULL))),
    CONSTRAINT chk_return_requests_amounts CHECK (quantity > 0 AND unit_price >= 0 AND refund_amount >= 0),
    CONSTRAINT chk_return_requests_window CHECK (return_window_days IS NULL OR return_window_days >= 30),
    CONSTRAINT chk_return_requests_problem CHECK (problem_reported
        OR (problem_description IS NULL AND problem_reported_at IS NULL AND claim_id IS NULL)),
    CONSTRAINT chk_return_requests_attempts CHECK (refund_attempts >= 0)
);

CREATE INDEX idx_return_requests_buyer ON return_requests (buyer_account_id, id);
CREATE INDEX idx_return_requests_store ON return_requests (store_id, status, id);
CREATE INDEX idx_return_requests_order ON return_requests (order_id);
-- El barrido busca las que vencen o reintentan: (estado, fecha límite), en lotes pequeños y con SKIP LOCKED.
CREATE INDEX idx_return_requests_sweep ON return_requests (status, next_action_at);

-- Solicitudes de información del vendedor al comprador (RF-052, RF-107). Solo una abierta a la vez: open_key vale 1
-- mientras está abierta y NULL al responderse, y el UNIQUE lo garantiza. El vencimiento de 24 h (A5) se calcula al
-- consultar (due_at), no se guarda como estado.
CREATE TABLE return_information_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    requested_by_account_id BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(10) NOT NULL,
    open_key TINYINT NULL DEFAULT 1,
    response_text VARCHAR(1000) NULL,
    responded_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_return_information_open UNIQUE (return_id, open_key),
    CONSTRAINT fk_return_information_return FOREIGN KEY (return_id) REFERENCES return_requests (id),
    CONSTRAINT chk_return_information_status CHECK (status IN ('OPEN', 'ANSWERED')),
    CONSTRAINT chk_return_information_open CHECK ((status = 'OPEN') = (open_key IS NOT NULL))
);

CREATE INDEX idx_return_information_return ON return_information_requests (return_id, id);

-- Línea de tiempo de negocio de la devolución (RF-051): cambios de estado y hechos que no cambian el estado (pedir o
-- responder información, elegir método, reportar un problema, reintentos de reembolso). Solo se inserta.
CREATE TABLE return_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT NULL,
    details VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_return_events_return FOREIGN KEY (return_id) REFERENCES return_requests (id)
);

CREATE INDEX idx_return_events_timeline ON return_events (return_id, id);

-- Imágenes de evidencia de la solicitud (RF-050): en la base junto a ella, como las de los reportes. Los listados
-- nunca leen esta tabla; solo el endpoint que sirve una imagen.
CREATE TABLE return_evidence_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    data LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_return_evidence_ordinal UNIQUE (return_id, ordinal),
    CONSTRAINT fk_return_evidence_return FOREIGN KEY (return_id) REFERENCES return_requests (id) ON DELETE CASCADE,
    CONSTRAINT chk_return_evidence_ordinal CHECK (ordinal >= 1)
);
