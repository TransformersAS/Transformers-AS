-- CU-27 Evaluar y aplicar suspensiones de tiendas.

-- Suspensión temporal de la operación comercial de una tienda. Una tienda tiene como máximo una suspensión vigente:
-- active_key vale 1 solo mientras está ACTIVE, y la clave única (store_id, active_key) impide una segunda.
CREATE TABLE store_suspensions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    applied_by_account_id BIGINT NOT NULL,
    closed_at DATETIME(6) NULL,
    closed_by_account_id BIGINT NULL,
    close_reason VARCHAR(1000) NULL,
    active_key TINYINT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_store_suspensions_active UNIQUE (store_id, active_key),
    CONSTRAINT fk_store_suspensions_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_suspensions_applied_by FOREIGN KEY (applied_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_store_suspensions_closed_by FOREIGN KEY (closed_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_store_suspensions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'LIFTED')),
    CONSTRAINT chk_store_suspensions_active CHECK ((status = 'ACTIVE') = (active_key IS NOT NULL)),
    CONSTRAINT chk_store_suspensions_dates CHECK (ends_at > starts_at),
    CONSTRAINT chk_store_suspensions_closed CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);

-- El barrido busca las vigentes que ya vencieron.
CREATE INDEX idx_store_suspensions_due ON store_suspensions (status, ends_at);
CREATE INDEX idx_store_suspensions_store ON store_suspensions (store_id, id);

-- Historia de cada suspensión: aplicación, cambios de duración, levantamiento anticipado y fin automático.
-- reactivation_blocked indica que al terminar la tienda no se reactivó por otra restricción vigente (A6).
CREATE TABLE store_suspension_changes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    suspension_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    previous_ends_at DATETIME(6) NULL,
    new_ends_at DATETIME(6) NULL,
    justification VARCHAR(1000) NULL,
    actor_account_id BIGINT NULL,
    reactivation_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_store_suspension_changes_suspension FOREIGN KEY (suspension_id) REFERENCES store_suspensions (id),
    CONSTRAINT fk_store_suspension_changes_actor FOREIGN KEY (actor_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_store_suspension_changes_type CHECK (change_type IN ('APPLIED', 'DURATION_CHANGED', 'LIFTED', 'EXPIRED'))
);

CREATE INDEX idx_store_suspension_changes_suspension ON store_suspension_changes (suspension_id, id);

-- Cada evaluación que cierra el administrador: sin medida (A1) o con la suspensión que aplicó.
CREATE TABLE store_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    reviewed_by_account_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    suspension_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_store_reviews_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_reviews_reviewer FOREIGN KEY (reviewed_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_store_reviews_suspension FOREIGN KEY (suspension_id) REFERENCES store_suspensions (id),
    CONSTRAINT chk_store_reviews_decision CHECK (decision IN ('NO_ACTION', 'SUSPENDED')),
    CONSTRAINT chk_store_reviews_suspension CHECK ((decision = 'SUSPENDED') = (suspension_id IS NOT NULL))
);

CREATE INDEX idx_store_reviews_store ON store_reviews (store_id, id);

-- Aclaración que el administrador pide al vendedor; este tiene hasta due_at (72 h) para responder (A2). Una sola
-- abierta por tienda: open_key vale 1 solo en OPEN. Una vencida sin respuesta pasa a EXPIRED al pedir otra.
CREATE TABLE store_clarification_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_id BIGINT NOT NULL,
    requested_by_account_id BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(10) NOT NULL,
    response_text VARCHAR(2000) NULL,
    responded_at DATETIME(6) NULL,
    responded_by_account_id BIGINT NULL,
    open_key TINYINT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_store_clarifications_open UNIQUE (store_id, open_key),
    CONSTRAINT fk_store_clarifications_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_store_clarifications_requester FOREIGN KEY (requested_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_store_clarifications_responder FOREIGN KEY (responded_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_store_clarifications_status CHECK (status IN ('OPEN', 'ANSWERED', 'EXPIRED')),
    CONSTRAINT chk_store_clarifications_due CHECK (due_at > requested_at),
    CONSTRAINT chk_store_clarifications_answer CHECK ((status = 'ANSWERED') = (responded_at IS NOT NULL)
        AND (response_text IS NULL) = (responded_at IS NULL)),
    CONSTRAINT chk_store_clarifications_open CHECK ((status = 'OPEN') = (open_key IS NOT NULL))
);

CREATE INDEX idx_store_clarifications_store ON store_clarification_requests (store_id, id);
