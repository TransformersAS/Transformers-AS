-- Public disposable fixture only. Password: Cu08RealTestPassword!
-- This SQL is fed ONLY to the new project's mysql, after Flyway has completed.
USE cu08_h02_validation;
START TRANSACTION;
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('cu08-h02@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',CURRENT_TIMESTAMP(6));
INSERT INTO user_account_roles(account_id,role)
SELECT id,'COMPRADOR' FROM user_accounts WHERE email='cu08-h02@example.test';
COMMIT;
