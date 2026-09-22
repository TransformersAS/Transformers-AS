-- Only executed inside the disposable compose.e2e.yaml database, after Flyway.
-- BCrypt of Cu08RealTestPassword!; this is a public test fixture, never a production account.
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('cu08-real@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',CURRENT_TIMESTAMP(6));
INSERT INTO user_account_roles(account_id,role)
SELECT id,'COMPRADOR' FROM user_accounts WHERE email='cu08-real@example.test';
