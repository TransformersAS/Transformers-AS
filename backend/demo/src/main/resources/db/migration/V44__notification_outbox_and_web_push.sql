CREATE TABLE notification_jobs (
 notification_id BIGINT PRIMARY KEY,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 locked_until DATETIME(6),
 last_error VARCHAR(200),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
 INDEX idx_notification_job_poll(status,available_at)
);
CREATE TABLE push_subscriptions (
 id VARCHAR(36) PRIMARY KEY,
 endpoint_hash CHAR(64) NOT NULL UNIQUE,
 endpoint VARCHAR(2048) NOT NULL,
 p256dh VARCHAR(150) NOT NULL,
 auth VARCHAR(100) NOT NULL,
 account_id BIGINT NOT NULL,
 recipient_type VARCHAR(16) NOT NULL,
 recipient_id BIGINT NOT NULL,
 session_primary_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(account_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
 FOREIGN KEY(session_primary_id) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
 INDEX idx_push_recipient(recipient_type,recipient_id)
);
CREATE TABLE push_deliveries (
 notification_id BIGINT NOT NULL,
 subscription_id VARCHAR(36) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(notification_id,subscription_id),
 FOREIGN KEY(notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
 FOREIGN KEY(subscription_id) REFERENCES push_subscriptions(id) ON DELETE CASCADE
);
