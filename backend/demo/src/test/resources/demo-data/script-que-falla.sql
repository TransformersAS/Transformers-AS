-- Solo para las pruebas: inserta un producto y luego falla. Nada de esto debe quedar en la base.
INSERT INTO products (name, description, price, stock, category, active, status, store_id)
VALUES ('Producto que no debe quedar', 'Prueba de atomicidad.', 1000, 1, 'Prueba', TRUE, 'ACTIVE', 1);
INSERT INTO tabla_que_no_existe (id) VALUES (1);
