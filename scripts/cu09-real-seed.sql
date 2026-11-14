-- Credencial pública de prueba: Cu08RealTestPassword!; solo base desechable CU-09.
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('cu09-real@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',CURRENT_TIMESTAMP(6));
INSERT INTO user_account_roles(account_id,role) SELECT id,'COMPRADOR' FROM user_accounts WHERE email='cu09-real@example.test';
INSERT INTO account_profiles(account_id,full_name,birth_date,phone) SELECT id,'Ana Perfil','2000-01-01','3001234567' FROM user_accounts WHERE email='cu09-real@example.test';
