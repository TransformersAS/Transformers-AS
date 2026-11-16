-- Identidades sintéticas CU08; sin tiendas, productos, pedidos ni registro de otros CU.
SET @hash='$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m';
INSERT INTO user_accounts(email,password_hash,status,email_verified_at) VALUES
('cu08-main@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-control@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-password@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-recovery@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-pending@example.test',@hash,'ACTIVA',NULL),
('cu08-mail@example.test',@hash,'ACTIVA',NULL),
('cu08-inactive@example.test',@hash,'INACTIVA',NOW(6)),
('cu08-restricted@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-global@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-expiry@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-persistent@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-sessions@example.test',@hash,'ACTIVA',NOW(6)),
('cu08-fixture-admin@example.test',@hash,'ACTIVA',NOW(6));
INSERT INTO user_account_roles(account_id,role) SELECT id,'COMPRADOR' FROM user_accounts WHERE email<>'cu08-fixture-admin@example.test';
INSERT INTO user_account_roles(account_id,role) SELECT id,'VENDEDOR' FROM user_accounts WHERE email IN ('cu08-main@example.test','cu08-restricted@example.test','cu08-global@example.test');
INSERT INTO user_account_roles(account_id,role) SELECT id,'ADMIN' FROM user_accounts WHERE email='cu08-fixture-admin@example.test';
SET @actor=(SELECT id FROM user_accounts WHERE email='cu08-fixture-admin@example.test');
INSERT INTO account_restrictions(account_id,scope,role,reason,permanent,starts_at,status,applied_by_account_id,created_at)
SELECT id,'ROLE','VENDEDOR','Fixture CU08: rol vendedor restringido',TRUE,NOW(6),'ACTIVE',@actor,NOW(6) FROM user_accounts WHERE email='cu08-restricted@example.test';
INSERT INTO account_restrictions(account_id,scope,role,reason,permanent,starts_at,status,applied_by_account_id,created_at)
SELECT id,'ACCOUNT','ACCOUNT','Fixture CU08: restricción global administrativa',TRUE,NOW(6),'ACTIVE',@actor,NOW(6) FROM user_accounts WHERE email='cu08-global@example.test';
SELECT JSON_OBJECT('main','cu08-main@example.test','control','cu08-control@example.test','password','cu08-password@example.test','recovery','cu08-recovery@example.test','pending','cu08-pending@example.test','mail','cu08-mail@example.test','inactive','cu08-inactive@example.test','restricted','cu08-restricted@example.test','global','cu08-global@example.test','expiry','cu08-expiry@example.test','persistent','cu08-persistent@example.test','sessions','cu08-sessions@example.test');
