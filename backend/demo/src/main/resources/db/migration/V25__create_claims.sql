-- CU-13: tramitar una reclamación de compra.
-- Una reclamación es de un comprador sobre un producto de una de sus compras. El vendedor de esa tienda puede pedir
-- información o proponer una solución; si no hay acuerdo, el comprador la escala y un agente de soporte decide.
CREATE TABLE claims (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    -- Copia del nombre y de lo pagado por ese producto al abrir la reclamación (el pedido no vuelve a consultarse).
    product_name VARCHAR(255) NOT NULL,
    item_total DECIMAL(19, 2) NOT NULL,
    buyer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- Última solución propuesta por el vendedor (y el reembolso que ofrece, si ofrece alguno).
    proposal_text VARCHAR(1000) NULL,
    proposed_refund DECIMAL(19, 2) NULL,
    -- Cómo terminó: SOLUTION_ACCEPTED (el comprador aceptó), REFUND_GRANTED o REJECTED (decidió soporte).
    resolution VARCHAR(20) NULL,
    resolution_note VARCHAR(1000) NULL,
    refund_amount DECIMAL(19, 2) NULL,
    refund_status VARCHAR(10) NULL,
    resolved_by_account_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_claims_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_claims_buyer FOREIGN KEY (buyer_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_claims_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_claims_resolved_by FOREIGN KEY (resolved_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_claims_status CHECK (status IN ('OPEN', 'INFO_REQUESTED', 'SOLUTION_PROPOSED', 'ESCALATED', 'RESOLVED')),
    CONSTRAINT chk_claims_resolution CHECK (resolution IS NULL
        OR resolution IN ('SOLUTION_ACCEPTED', 'REFUND_GRANTED', 'REJECTED')),
    CONSTRAINT chk_claims_amounts CHECK (item_total >= 0 AND (proposed_refund IS NULL OR proposed_refund >= 0)
        AND (refund_amount IS NULL OR refund_amount > 0))
);

CREATE INDEX idx_claims_buyer ON claims (buyer_account_id, id);
CREATE INDEX idx_claims_store ON claims (store_id, id);
CREATE INDEX idx_claims_status ON claims (status, id);
CREATE INDEX idx_claims_order_product ON claims (order_id, product_id);

-- Evidencias como enlaces (fotos, capturas...), en el orden en que se enviaron.
CREATE TABLE claim_evidences (
    claim_id BIGINT NOT NULL,
    position INT NOT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (claim_id, position),
    CONSTRAINT fk_claim_evidences_claim FOREIGN KEY (claim_id) REFERENCES claims (id)
);

-- Hilo del caso: quién dijo o decidió qué, en orden.
CREATE TABLE claim_messages (
    claim_id BIGINT NOT NULL,
    position INT NOT NULL,
    author VARCHAR(10) NOT NULL,
    kind VARCHAR(15) NOT NULL,
    author_account_id BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (claim_id, position),
    CONSTRAINT fk_claim_messages_claim FOREIGN KEY (claim_id) REFERENCES claims (id),
    CONSTRAINT chk_claim_messages_author CHECK (author IN ('BUYER', 'SELLER', 'SUPPORT')),
    CONSTRAINT chk_claim_messages_kind CHECK (kind IN ('MESSAGE', 'INFO_REQUEST', 'PROPOSAL', 'ESCALATION', 'DECISION'))
);
