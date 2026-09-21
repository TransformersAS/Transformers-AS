-- CU-12: registrar una cuenta de vendedor.
-- Una cuenta habilita el rol VENDEDOR con una tienda de nombre único (CU-18 ya lo garantiza), acepta las condiciones y,
-- cuando hace falta, verifica su correo.

-- Correo verificado. Las cuentas que ya existían antes de este caso de uso se consideran verificadas: se crearon sin
-- verificación de correo y no se les debe pedir ahora. Las cuentas nuevas empiezan sin verificar (NULL).
ALTER TABLE user_accounts ADD COLUMN email_verified_at DATETIME(6) NULL;
UPDATE user_accounts SET email_verified_at = CURRENT_TIMESTAMP(6);

-- Enlace de verificación enviado por correo. Solo se guarda el hash (SHA-256) del token, como en la recuperación de
-- contraseña; el token en claro solo viaja por el correo. Vale 24 horas y se usa una sola vez.
CREATE TABLE email_verification_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    CONSTRAINT uk_email_verification_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_account FOREIGN KEY (account_id) REFERENCES user_accounts (id) ON DELETE CASCADE,
    INDEX idx_email_verification_account (account_id)
);

-- Constancia de que la cuenta aceptó las condiciones para vender, con la versión que leyó y cuándo.
CREATE TABLE seller_terms_acceptances (
    account_id BIGINT NOT NULL,
    terms_version VARCHAR(20) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, terms_version),
    CONSTRAINT fk_seller_terms_account FOREIGN KEY (account_id) REFERENCES user_accounts (id)
);
