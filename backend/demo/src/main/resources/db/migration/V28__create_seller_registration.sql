-- CU-12: registrar una cuenta de vendedor.
-- Una cuenta habilita el rol VENDEDOR con una tienda de nombre único (CU-18 ya lo garantiza), acepta las condiciones y,
-- cuando hace falta, confirma su registro.

-- Registro confirmado. Las cuentas que ya existían antes de este caso de uso se consideran confirmadas: se crearon sin
-- este paso y no se les debe pedir ahora. Las cuentas nuevas empiezan sin confirmar (NULL).
-- Primera entrega: la confirmación se hace escribiendo el nombre de la tienda registrada; no comprueba el buzón del
-- correo. Enviar un código secreto por correo queda para una entrega posterior.
ALTER TABLE user_accounts ADD COLUMN email_verified_at DATETIME(6) NULL;
UPDATE user_accounts SET email_verified_at = CURRENT_TIMESTAMP(6);

-- Constancia de que la cuenta aceptó las condiciones para vender, con la versión que leyó y cuándo.
CREATE TABLE seller_terms_acceptances (
    account_id BIGINT NOT NULL,
    terms_version VARCHAR(20) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, terms_version),
    CONSTRAINT fk_seller_terms_account FOREIGN KEY (account_id) REFERENCES user_accounts (id)
);
