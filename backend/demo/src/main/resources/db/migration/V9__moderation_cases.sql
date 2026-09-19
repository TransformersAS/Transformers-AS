

CREATE TABLE moderation_cases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    content_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    assigned_agent_id VARCHAR(255),
    report_count INT NOT NULL DEFAULT 0,

    open_key INT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    opened_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6),
    CONSTRAINT uq_moderation_case_open UNIQUE (content_type, content_id, open_key)
);

CREATE INDEX idx_moderation_cases_status ON moderation_cases(status, report_count);


INSERT INTO moderation_cases (content_type, content_id, status, assigned_agent_id, report_count, open_key,
                              version, opened_at, resolved_at)
SELECT r.content_type,
       r.content_id,
       CASE WHEN SUM(r.status NOT IN ('RESUELTO', 'CERRADO')) = 0 THEN 'RESUELTO'
            WHEN SUM(r.status IN ('EN_INVESTIGACION', 'INFORMACION_SOLICITADA', 'ESCALADO')) > 0 THEN 'EN_REVISION'
            ELSE 'PENDIENTE' END,
       MAX(r.assigned_agent_id),
       COUNT(*),
       CASE WHEN SUM(r.status NOT IN ('RESUELTO', 'CERRADO')) = 0 THEN NULL ELSE 1 END,
       0,
       MIN(r.created_at),
       CASE WHEN SUM(r.status NOT IN ('RESUELTO', 'CERRADO')) = 0 THEN MAX(r.resolved_at) END
FROM reports r
GROUP BY r.content_type, r.content_id;

ALTER TABLE reports ADD COLUMN case_id BIGINT NULL;

UPDATE reports r
    JOIN moderation_cases c ON c.content_type = r.content_type AND c.content_id = r.content_id
SET r.case_id = c.id;

ALTER TABLE moderation_actions ADD COLUMN case_id BIGINT NULL;

UPDATE moderation_actions a
    JOIN reports r ON r.id = a.report_id
SET a.case_id = r.case_id;

DELETE FROM moderation_actions WHERE decision NOT IN ('MANTENER', 'OCULTAR_TEMPORALMENTE', 'RETIRAR');

UPDATE moderation_actions SET previous_status = CASE previous_status
        WHEN 'EN_INVESTIGACION' THEN 'EN_REVISION'
        WHEN 'INFORMACION_SOLICITADA' THEN 'INFO_SOLICITADA'
        WHEN 'ESCALADO' THEN 'EN_REVISION'
        WHEN 'CERRADO' THEN 'RESUELTO'
        ELSE previous_status END,
    new_status = CASE new_status
        WHEN 'EN_INVESTIGACION' THEN 'EN_REVISION'
        WHEN 'INFORMACION_SOLICITADA' THEN 'INFO_SOLICITADA'
        WHEN 'ESCALADO' THEN 'EN_REVISION'
        WHEN 'CERRADO' THEN 'RESUELTO'
        ELSE new_status END;

ALTER TABLE moderation_actions DROP FOREIGN KEY fk_moderation_actions_report;
ALTER TABLE moderation_actions DROP COLUMN report_id;
ALTER TABLE moderation_actions
    MODIFY case_id BIGINT NOT NULL,
    ADD COLUMN measure_result VARCHAR(50) NOT NULL DEFAULT 'APLICADA',
    ADD CONSTRAINT fk_moderation_actions_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id);

DROP INDEX idx_reports_status ON reports;

ALTER TABLE reports
    MODIFY case_id BIGINT NOT NULL,
    DROP COLUMN status,
    DROP COLUMN assigned_agent_id,
    DROP COLUMN resolved_at,
    DROP COLUMN updated_at,
    ADD CONSTRAINT fk_reports_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id);

CREATE INDEX idx_reports_reporter ON reports(case_id, reporter_id);


CREATE TABLE information_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    target_role VARCHAR(30) NOT NULL,
    target_user_id VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    responded_at DATETIME(6),
    response_text TEXT,
    CONSTRAINT fk_information_requests_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id)
);

CREATE INDEX idx_information_requests_due ON information_requests(status, due_at);
CREATE INDEX idx_information_requests_case ON information_requests(case_id, status);


CREATE TABLE content_moderation_state (
    content_type VARCHAR(100) NOT NULL,
    content_id VARCHAR(255) NOT NULL,
    state VARCHAR(50) NOT NULL,
    case_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (content_type, content_id),
    CONSTRAINT fk_content_moderation_state_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id)
);

CREATE INDEX idx_content_moderation_state_type ON content_moderation_state(content_type, state);


CREATE TABLE moderation_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    template VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6),
    last_error VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6),
    CONSTRAINT fk_moderation_notifications_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id)
);

CREATE INDEX idx_moderation_notifications_dispatch ON moderation_notifications(channel, status, next_attempt_at);
CREATE INDEX idx_moderation_notifications_recipient ON moderation_notifications(recipient_id, channel);


CREATE TABLE moderation_referrals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    justification TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_moderation_referrals_case FOREIGN KEY (case_id) REFERENCES moderation_cases(id)
);

CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
