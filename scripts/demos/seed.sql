-- Sólo la base NUEVA sustentacion, validada por el launcher. Datos sintéticos.
-- Mismo BCrypt público de los fixtures CU-07/08/09 existentes.
SET @hash='$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m';
INSERT INTO user_accounts(email,password_hash,status,email_verified_at) VALUES
 ('buyer@example.test',@hash,'ACTIVA',NOW(6)),
 ('roles@example.test',@hash,'ACTIVA',NOW(6)),
 ('seller@example.test',@hash,'ACTIVA',NOW(6));
SET @buyer=(SELECT id FROM user_accounts WHERE email='buyer@example.test');
SET @roles=(SELECT id FROM user_accounts WHERE email='roles@example.test');
SET @seller=(SELECT id FROM user_accounts WHERE email='seller@example.test');
INSERT INTO user_account_roles VALUES (@buyer,'COMPRADOR'),(@roles,'COMPRADOR'),(@roles,'VENDEDOR'),(@seller,'VENDEDOR');
INSERT INTO account_profiles(account_id,full_name,birth_date,phone) VALUES (@buyer,'Persona Demostración','2000-01-01','3000000000');
UPDATE stores SET owner_account_id=@seller WHERE id=1;
INSERT INTO products(name,description,price,stock,category,active,store_id) VALUES ('Producto sustentación CU-10','Fixture sintético, no compra real',12000,20,'Hogar',TRUE,1);
SET @product=LAST_INSERT_ID();
INSERT INTO addresses(account_id,recipient_name,street,city,department,phone) VALUES (@buyer,'Persona Demo','Calle Demo 1','Bogotá','Bogotá','3000000000');
SET @address=LAST_INSERT_ID();
INSERT INTO orders(account_id,status,total,address_id,shipping_method,transaction_id,created_at,store_id,payment_status,delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone,discount_amount,shipping_cost,seller_name_snapshot)
VALUES (@buyer,'CONFIRMED',12000,@address,'STANDARD','SUSTENTACION-FIXTURE',NOW(),1,'APPROVED','Persona Demo','Calle Demo 1','Bogotá','Bogotá','3000000000',0,0,'Tienda principal');
SET @order=LAST_INSERT_ID();
INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal,unit_cost) VALUES (@order,@product,'Producto sustentación CU-10',1,12000,12000,3000);
INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,reason,correlation_id,created_at) VALUES (@order,NULL,'CONFIRMED','SYSTEM','Fixture SQL para CU-10/CU-11; no se demuestra compra','sustentacion',NOW(6));
SELECT JSON_OBJECT('buyer','buyer@example.test','roles','roles@example.test','seller','seller@example.test','buyerId',@buyer,'sellerId',@seller,'rolesId',@roles,'productId',@product,'orderId',@order,'productName','Producto sustentación CU-10');
