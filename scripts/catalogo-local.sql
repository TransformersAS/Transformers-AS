-- Catalogo de ejemplo exclusivo para desarrollo local.
-- Repetir la carga no modifica precios, existencias ni estados de productos existentes.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO products (name, description, price, stock, category, active, status, store_id)
SELECT catalog.name, catalog.description, catalog.price, catalog.stock, catalog.category, TRUE, 'ACTIVE', 1
FROM (
    SELECT 'Camiseta basica demo' AS name, 'Camiseta de algodon para uso diario.' AS description, 35000 AS price, 40 AS stock, 'Ropa' AS category
    UNION ALL SELECT 'Jean clasico demo', 'Pantalon jean de corte clasico.', 95000, 25, 'Ropa'
    UNION ALL SELECT 'Chaqueta ligera demo', 'Chaqueta ligera para clima fresco.', 145000, 15, 'Ropa'
    UNION ALL SELECT 'Tenis urbanos demo', 'Calzado comodo para uso diario.', 120000, 30, 'Calzado'
    UNION ALL SELECT 'Mochila urbana demo', 'Mochila con compartimento principal amplio.', 85000, 20, 'Accesorios'
    UNION ALL SELECT 'Reloj digital demo', 'Reloj digital de uso cotidiano.', 75000, 18, 'Accesorios'
    UNION ALL SELECT 'Audifonos bluetooth demo', 'Audifonos inalambricos de ejemplo.', 99000, 35, 'Tecnologia'
    UNION ALL SELECT 'Teclado USB demo', 'Teclado para computador con conexion USB.', 65000, 22, 'Tecnologia'
    UNION ALL SELECT 'Lampara de escritorio demo', 'Lampara para escritorio y lectura.', 55000, 16, 'Hogar'
    UNION ALL SELECT 'Botella reutilizable demo', 'Botella reutilizable para llevar agua.', 28000, 50, 'Hogar'
) AS catalog
WHERE NOT EXISTS (
    SELECT 1 FROM products p WHERE p.store_id = 1 AND p.name = catalog.name
);
COMMIT;
SELECT COUNT(*) AS productos_catalogo_tienda_principal FROM products WHERE store_id = 1;
