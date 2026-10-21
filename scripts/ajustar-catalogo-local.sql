-- Ajuste puntual del catalogo local solicitado para la demostracion.
-- Conserva IDs e historial: retira publicaciones, no borra productos ni pedidos.
-- Ejecutar solo en desarrollo local, sin cargas concurrentes.
START TRANSACTION;

-- Conserva la primera lampara activa de la tienda principal.
SET @lampara_conservada = (
    SELECT MIN(id) FROM products
    WHERE name = 'Lampara LED de Mesa' AND store_id = 1 AND active = TRUE
);
UPDATE products SET active = FALSE, status = 'RETIRED'
WHERE name = 'Lampara LED de Mesa' AND store_id = 1
    AND active = TRUE AND id <> @lampara_conservada;

UPDATE products SET active = FALSE, status = 'RETIRED'
WHERE name = 'Reloj digital demo' AND store_id = 1;

INSERT INTO products (name, description, price, stock, category, active, status, store_id)
SELECT 'Cafetera de goteo demo', 'Cafetera de goteo para preparar cafe en casa.',
       75000, 18, 'Hogar', TRUE, 'ACTIVE', 1
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM products WHERE name = 'Cafetera de goteo demo' AND store_id = 1
);
COMMIT;

SELECT id, name, price, active, status FROM products
WHERE store_id = 1 AND name IN (
    'Lampara LED de Mesa', 'Reloj digital demo', 'Cafetera de goteo demo'
) ORDER BY id;
