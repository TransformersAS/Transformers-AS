-- Fixture exclusivo CU07: identidad existente con otro rol, sin crear tienda/pedidos.
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('cu07-existing@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',NOW(6));
SET @existing=LAST_INSERT_ID();
INSERT INTO user_account_roles VALUES (@existing,'VENDEDOR');
INSERT INTO account_profiles(account_id,full_name,birth_date,phone)
VALUES (@existing,'Persona Existente','1990-01-01','3000000000');
SELECT JSON_OBJECT('existing','cu07-existing@example.test','existingId',@existing);
