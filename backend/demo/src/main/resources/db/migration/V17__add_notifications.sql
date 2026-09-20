-- Notificaciones internas del marketplace (D9). Contrato mínimo reutilizable por otros casos de uso.
-- event_key UNIQUE deduplica: el mismo evento nunca genera dos notificaciones (RF-123).
-- external_status refleja solo el aviso al servicio externo; un fallo externo nunca borra la notificación interna.
CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_type VARCHAR(16) NOT NULL,
    recipient_id BIGINT NULL,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id VARCHAR(64) NOT NULL,
    event_key VARCHAR(150) NOT NULL,
    external_status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_notifications_event_key UNIQUE (event_key),
    CONSTRAINT chk_notifications_recipient_type CHECK (recipient_type IN ('BUYER', 'STORE')),
    CONSTRAINT chk_notifications_external_status CHECK (external_status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notifications_attempts CHECK (attempts >= 0)
);

-- Lectura futura por destinatario y barrido de avisos externos pendientes o fallidos.
CREATE INDEX idx_notifications_recipient ON notifications (recipient_type, recipient_id, created_at);
CREATE INDEX idx_notifications_external_status ON notifications (external_status, created_at);
