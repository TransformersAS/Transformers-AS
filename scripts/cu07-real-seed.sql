-- Public test fixture, only loaded into the disposable CU-07 Compose project.
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('cu07-seller@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',CURRENT_TIMESTAMP(6));
INSERT INTO user_account_roles(account_id,role)
SELECT id,'VENDEDOR' FROM user_accounts WHERE email='cu07-seller@example.test';
