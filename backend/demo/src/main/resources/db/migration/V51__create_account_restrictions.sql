-- CU-22 Administrar cuentas y restricciones de acceso.

-- Restricción administrativa sobre una cuenta: alcanza toda la cuenta o un solo rol. Una cuenta tiene como máximo una
-- restricción vigente por alcance: active_key vale 1 solo mientras está ACTIVE, y la clave única (account_id, role,
-- active_key) impide una segunda sobre el mismo alcance. role guarda el rol afectado cuando scope = ROLE, o el valor
-- fijo 'ACCOUNT' cuando scope = ACCOUNT (así la unicidad también cubre la restricción de toda la cuenta).
CREATE TABLE account_restrictions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    scope VARCHAR(10) NOT NULL,
    role VARCHAR(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    permanent BOOLEAN NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    applied_by_account_id BIGINT NOT NULL,
    closed_at DATETIME(6) NULL,
    closed_by_account_id BIGINT NULL,
    close_reason VARCHAR(1000) NULL,
    active_key TINYINT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_account_restrictions_active UNIQUE (account_id, role, active_key),
    CONSTRAINT fk_account_restrictions_account FOREIGN KEY (account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_account_restrictions_applied_by FOREIGN KEY (applied_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_account_restrictions_closed_by FOREIGN KEY (closed_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_account_restrictions_scope CHECK (scope IN ('ACCOUNT', 'ROLE')),
    CONSTRAINT chk_account_restrictions_role CHECK ((scope = 'ACCOUNT') = (role = 'ACCOUNT')),
    CONSTRAINT chk_account_restrictions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'WITHDRAWN')),
    CONSTRAINT chk_account_restrictions_active CHECK ((status = 'ACTIVE') = (active_key IS NOT NULL)),
    CONSTRAINT chk_account_restrictions_closed CHECK ((status = 'ACTIVE') = (closed_at IS NULL)),
    CONSTRAINT chk_account_restrictions_close_reason CHECK (status = 'ACTIVE' OR close_reason IS NOT NULL),
    CONSTRAINT chk_account_restrictions_permanent CHECK (permanent = (ends_at IS NULL)),
    CONSTRAINT chk_account_restrictions_dates CHECK (ends_at IS NULL OR ends_at > starts_at)
);

-- El barrido busca las temporales vigentes que ya vencieron.
CREATE INDEX idx_account_restrictions_due ON account_restrictions (status, ends_at);
CREATE INDEX idx_account_restrictions_account ON account_restrictions (account_id, id);

-- Historia de cada restricción: aplicación, cambios de duración o de permanencia, retiro anticipado y fin automático.
CREATE TABLE account_restriction_changes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restriction_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    previous_ends_at DATETIME(6) NULL,
    new_ends_at DATETIME(6) NULL,
    justification VARCHAR(1000) NULL,
    actor_account_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_account_restriction_changes_restriction FOREIGN KEY (restriction_id)
        REFERENCES account_restrictions (id),
    CONSTRAINT fk_account_restriction_changes_actor FOREIGN KEY (actor_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_account_restriction_changes_type CHECK (change_type IN
        ('APPLIED', 'DURATION_CHANGED', 'MADE_PERMANENT', 'MADE_TEMPORARY', 'WITHDRAWN', 'EXPIRED'))
);

CREATE INDEX idx_account_restriction_changes_restriction ON account_restriction_changes (restriction_id, id);

-- Aclaración que el administrador pide al usuario (paso 8, A3); este tiene hasta due_at (72 h) para responder (A4).
-- Una sola abierta por cuenta: open_key vale 1 solo en OPEN.
CREATE TABLE account_information_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    requested_by_account_id BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(10) NOT NULL,
    response_text VARCHAR(2000) NULL,
    responded_at DATETIME(6) NULL,
    open_key TINYINT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_account_information_open UNIQUE (account_id, open_key),
    CONSTRAINT fk_account_information_account FOREIGN KEY (account_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_account_information_requester FOREIGN KEY (requested_by_account_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_account_information_status CHECK (status IN ('OPEN', 'ANSWERED', 'EXPIRED')),
    CONSTRAINT chk_account_information_due CHECK (due_at > requested_at),
    CONSTRAINT chk_account_information_answer CHECK ((status = 'ANSWERED') = (responded_at IS NOT NULL)
        AND (response_text IS NULL) = (responded_at IS NULL)),
    CONSTRAINT chk_account_information_open CHECK ((status = 'OPEN') = (open_key IS NOT NULL))
);

CREATE INDEX idx_account_information_account ON account_information_requests (account_id, id);
