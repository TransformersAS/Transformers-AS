-- Solo security_zap en MySQL nuevo y tmpfs. Fixture pública, nunca producción.
-- Contraseña ficticia conocida únicamente para este recorrido de prueba.
START TRANSACTION;
INSERT INTO user_accounts(email,password_hash,status,email_verified_at)
VALUES ('zap-buyer@example.test','$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m','ACTIVA',CURRENT_TIMESTAMP(6));
SET @buyer = LAST_INSERT_ID();
INSERT INTO user_account_roles(account_id,role) VALUES (@buyer,'COMPRADOR');
INSERT INTO carts(account_id) VALUES (@buyer);
INSERT INTO account_profiles(account_id,full_name,birth_date,phone)
VALUES (@buyer,'Comprador ficticio ZAP','2000-01-01','3000000000');
INSERT INTO products(name,description,price,stock,category,active,store_id,status)
VALUES ('Producto ficticio ZAP','Fixture exclusiva de lectura',15000,10,'Pruebas',TRUE,1,'ACTIVE');
SET @product = LAST_INSERT_ID();
INSERT INTO product_images(product_id,position,url) VALUES (@product,0,'/favicon.png');
INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (@product,0,'Estándar',15000,10);
INSERT INTO addresses(account_id,recipient_name,street,city,department,postal_code,phone)
VALUES (@buyer,'Destinatario ficticio','Calle ficticia 1','Bogotá','Cundinamarca','110111','3000000000');
COMMIT;
