CREATE TABLE user_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_accounts_email UNIQUE (email),
    CONSTRAINT chk_user_accounts_status CHECK (status IN ('ACTIVA', 'INACTIVA')),
    CONSTRAINT chk_user_accounts_password_bcrypt CHECK (
        REGEXP_LIKE(password_hash, '^[$]2[aby][$](0[4-9]|[12][0-9]|3[01])[$][./A-Za-z0-9]{53}$', 'c')
    )
);

CREATE TABLE user_account_roles (
    account_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (account_id, role),
    CONSTRAINT fk_user_account_roles_account FOREIGN KEY (account_id)
        REFERENCES user_accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_account_roles_role CHECK (role IN ('COMPRADOR', 'VENDEDOR', 'ADMIN', 'SOPORTE'))
);
