-- Bitácora de auditoría append-only (RNF-009). Solo se inserta; no existe API de actualización ni borrado.
-- details nunca debe contener datos personales (dirección, teléfono).
CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    details VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_audit_events_actor CHECK (actor_type IN ('SELLER', 'BUYER', 'SYSTEM', 'LOGISTICS')),
    CONSTRAINT chk_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'PENDING'))
);

CREATE INDEX idx_audit_events_entity ON audit_events (entity_type, entity_id, id);
CREATE INDEX idx_audit_events_correlation ON audit_events (correlation_id);
