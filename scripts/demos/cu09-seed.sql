-- CU09 exclusivo. Cuentas/procesos sintéticos de esta base NUEVA; no demuestra compras, pagos ni otros CU.
SET @hash='$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m';
INSERT INTO user_accounts(email,password_hash,status,email_verified_at) VALUES
('cu09-main@example.test',@hash,'ACTIVA',NOW(6)),('cu09-validation@example.test',@hash,'ACTIVA',NOW(6)),
('cu09-email@example.test',@hash,'ACTIVA',NOW(6)),('cu09-preferences@example.test',@hash,'ACTIVA',NOW(6)),
('cu09-blocked@example.test',@hash,'ACTIVA',NOW(6)),('cu09-closure@example.test',@hash,'ACTIVA',NOW(6)),
('cu09-failure@example.test',@hash,'ACTIVA',NOW(6)),('cu09-control@example.test',@hash,'ACTIVA',NOW(6)),
('cu09-real@example.test',@hash,'ACTIVA',NOW(6));
INSERT INTO user_account_roles(account_id,role) SELECT id,'COMPRADOR' FROM user_accounts;
INSERT INTO user_account_roles(account_id,role) SELECT id,'VENDEDOR' FROM user_accounts WHERE email='cu09-closure@example.test';
INSERT INTO account_profiles(account_id,full_name,birth_date,phone)
SELECT id,CASE WHEN email='cu09-real@example.test' THEN 'Ana Perfil' ELSE 'Persona Demostración' END,'2000-01-01','3000000000' FROM user_accounts;
INSERT INTO account_profile_settings(account_id,promotions,marketplace_news,version) SELECT id,FALSE,TRUE,0 FROM user_accounts;
SET @blocked=(SELECT id FROM user_accounts WHERE email='cu09-blocked@example.test');
SET @closure=(SELECT id FROM user_accounts WHERE email='cu09-closure@example.test');
INSERT INTO stores(name,owner_account_id) VALUES ('Tienda fixture cierre CU09',@closure);
SET @store=LAST_INSERT_ID();
INSERT INTO products(name,description,price,stock,category,active,store_id) VALUES ('Producto fixture bloqueos CU09','Sólo preparación SQL',12000,5,'Hogar',TRUE,1);
SET @product=LAST_INSERT_ID();
INSERT INTO products(name,description,price,stock,category,active,store_id) VALUES ('Producto histórico cierre CU09','Sólo preparación SQL',12000,5,'Hogar',TRUE,@store);
SET @historicalProduct=LAST_INSERT_ID();
INSERT INTO addresses(account_id,recipient_name,street,city,department,phone) VALUES (@blocked,'Persona Fixture','Calle Fixture 1','Bogotá','Bogotá','3000000000');
SET @blockedAddress=LAST_INSERT_ID();
INSERT INTO addresses(account_id,recipient_name,street,city,department,phone) VALUES (@closure,'Persona Fixture','Calle Fixture 2','Bogotá','Bogotá','3000000000');
SET @closureAddress=LAST_INSERT_ID();
INSERT INTO orders(account_id,status,total,address_id,shipping_method,transaction_id,created_at,store_id,payment_status,delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone,discount_amount,shipping_cost,seller_name_snapshot)
VALUES (@blocked,'CONFIRMED',12000,@blockedAddress,'STANDARD','CU09-PENDING-FIXTURE',NOW(),1,'APPROVED','Persona Fixture','Calle Fixture','Bogotá','Bogotá','3000000000',0,0,'Tienda principal'),
(@blocked,'DELIVERED',12000,@blockedAddress,'STANDARD','CU09-CLAIM-FIXTURE',NOW(),1,'APPROVED','Persona Fixture','Calle Fixture','Bogotá','Bogotá','3000000000',0,0,'Tienda principal'),
(@blocked,'DELIVERED',12000,@blockedAddress,'STANDARD','CU09-RETURN-FIXTURE',NOW(),1,'APPROVED','Persona Fixture','Calle Fixture','Bogotá','Bogotá','3000000000',0,0,'Tienda principal'),
(@closure,'DELIVERED',12000,@closureAddress,'STANDARD','CU09-HISTORY-FIXTURE',NOW(),@store,'REFUNDED','Persona Fixture','Calle Fixture','Bogotá','Bogotá','3000000000',0,0,'Tienda fixture cierre CU09');
INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal,unit_cost)
SELECT id,CASE WHEN account_id=@closure THEN @historicalProduct ELSE @product END,'Producto fixture CU09',1,12000,12000,3000 FROM orders;
INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,reason,correlation_id,created_at)
SELECT id,NULL,status,'SYSTEM','Fixture histórico CU09; no demuestra compra','cu09-fixture',NOW(6) FROM orders;
INSERT INTO claims(order_id,product_id,product_name,item_total,buyer_account_id,store_id,description,status,created_at,updated_at)
SELECT o.id,i.product_id,'Producto fixture CU09',12000,o.account_id,o.store_id,'Reclamación fixture CU09',IF(o.account_id=@closure,'RESOLVED','OPEN'),NOW(),NOW()
FROM orders o JOIN order_items i ON i.order_id=o.id WHERE o.transaction_id IN ('CU09-CLAIM-FIXTURE','CU09-HISTORY-FIXTURE');
INSERT INTO return_requests(order_id,order_item_id,product_id,product_name,quantity,unit_price,refund_amount,buyer_account_id,store_id,status,reason_code,description,created_at,updated_at)
SELECT o.id,i.id,i.product_id,'Producto fixture CU09',1,12000,12000,o.account_id,o.store_id,IF(o.account_id=@closure,'FINISHED','REQUESTED'),'OTHER','Devolución fixture CU09',NOW(),NOW()
FROM orders o JOIN order_items i ON i.order_id=o.id WHERE o.transaction_id IN ('CU09-RETURN-FIXTURE','CU09-HISTORY-FIXTURE');
INSERT INTO refunds(order_id,amount,idempotency_key,status,correlation_id,created_at,updated_at)
SELECT id,12000,'cu09-history-refund','COMPLETED','cu09-fixture',NOW(),NOW() FROM orders WHERE account_id=@closure;
SELECT JSON_OBJECT('accounts',(SELECT JSON_OBJECTAGG(SUBSTRING_INDEX(email,'@',1),id) FROM user_accounts),
'main','cu09-main@example.test','validation','cu09-validation@example.test','email','cu09-email@example.test','newEmail','cu09-new@example.test',
'preferences','cu09-preferences@example.test','preferencesEmail','cu09-preferences-new@example.test','blocked','cu09-blocked@example.test',
'closure','cu09-closure@example.test','failure','cu09-failure@example.test','control','cu09-control@example.test','legacy','cu09-real@example.test');
