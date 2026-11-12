-- CU09: preferencias únicas por identidad; las comunicaciones esenciales no son configurables.
CREATE TABLE account_profile_settings (
    account_id BIGINT NOT NULL PRIMARY KEY,
    promotions BOOLEAN NOT NULL DEFAULT FALSE,
    marketplace_news BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_profile_settings_account FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);
-- Mecanismo independiente de la verificación inicial de CU08. Nunca se guarda el código en claro.
CREATE TABLE account_email_changes (
    account_id BIGINT NOT NULL PRIMARY KEY,
    previous_email VARCHAR(254) NOT NULL,
    new_email VARCHAR(254) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    credential_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    CONSTRAINT fk_email_change_account FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);
