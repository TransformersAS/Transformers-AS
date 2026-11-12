-- Profiles are optional for legacy accounts; security identity remains in user_accounts.
CREATE TABLE account_profiles (
    account_id BIGINT NOT NULL PRIMARY KEY,
    full_name VARCHAR(150) NOT NULL,
    birth_date DATE NOT NULL,
    phone VARCHAR(32) NOT NULL,
    CONSTRAINT fk_account_profile FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);
CREATE TABLE buyer_consents (
    account_id BIGINT NOT NULL,
    terms_version VARCHAR(20) NOT NULL,
    privacy_version VARCHAR(20) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, terms_version, privacy_version),
    CONSTRAINT fk_buyer_consent FOREIGN KEY (account_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);
