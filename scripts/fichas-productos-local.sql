-- SOLO desarrollo: fichas ficticias de los diez productos del catalogo demo.
-- No se modifican precios, existencias, estados ni especificaciones ya editadas.
SET NAMES utf8mb4;
START TRANSACTION;
SET @fichas = '[
 {"name":"Camiseta basica demo","previous":"Camiseta de algodon para uso diario.","description":"Camiseta de corte regular para uso diario, con cuello redondo y manga corta. Combina con prendas casuales. Ficha ficticia de demostracion.","specs":[{"name":"Material","value":"Algodon"},{"name":"Tipo de cuello","value":"Redondo"},{"name":"Tipo de manga","value":"Corta"}]},
 {"name":"Jean clasico demo","previous":"Pantalon jean de corte clasico.","description":"Pantalon de mezclilla de corte clasico, con bolsillos y cierre frontal. Pensado para un estilo casual. Ficha ficticia de demostracion.","specs":[{"name":"Material","value":"Mezclilla"},{"name":"Corte","value":"Recto"},{"name":"Color","value":"Azul"}]},
 {"name":"Chaqueta ligera demo","previous":"Chaqueta ligera para clima fresco.","description":"Chaqueta ligera para clima fresco, con cierre frontal y bolsillos laterales. Ficha ficticia de demostracion.","specs":[{"name":"Material","value":"Poliester"},{"name":"Tipo de cierre","value":"Cremallera"},{"name":"Uso recomendado","value":"Casual"}]},
 {"name":"Tenis urbanos demo","previous":"Calzado comodo para uso diario.","description":"Calzado urbano con ajuste mediante cordones y suela de caucho para recorridos cotidianos. Ficha ficticia de demostracion.","specs":[{"name":"Material de la suela","value":"Caucho"},{"name":"Tipo de cierre","value":"Cordones"},{"name":"Uso recomendado","value":"Urbano"}]},
 {"name":"Mochila urbana demo","previous":"Mochila con compartimento principal amplio.","description":"Mochila para actividades cotidianas, con compartimento principal y correas ajustables. Ficha ficticia de demostracion.","specs":[{"name":"Capacidad","value":"20 L"},{"name":"Material","value":"Poliester"},{"name":"Correas","value":"Ajustables"}]},
 {"name":"Cafetera de goteo demo","previous":"Cafetera de goteo para preparar cafe en casa.","description":"Cafetera de goteo para preparar cafe en casa, con jarra de vidrio y filtro reutilizable. Utiliza cafe molido. Ficha ficticia de demostracion.","specs":[{"name":"Tipo","value":"De goteo"},{"name":"Capacidad","value":"600 ml"},{"name":"Tipo de cafe","value":"Molido"},{"name":"Filtro","value":"Reutilizable"}]},
 {"name":"Audifonos bluetooth demo","previous":"Audifonos inalambricos de ejemplo.","description":"Audifonos de diadema con conexion inalambrica para escuchar musica y realizar llamadas. Ficha ficticia de demostracion.","specs":[{"name":"Conectividad","value":"Bluetooth"},{"name":"Formato","value":"Diadema"},{"name":"Microfono","value":"Integrado"}]},
 {"name":"Teclado USB demo","previous":"Teclado para computador con conexion USB.","description":"Teclado de escritorio con conexion USB por cable y distribucion en espanol para tareas de oficina. Ficha ficticia de demostracion.","specs":[{"name":"Conectividad","value":"USB por cable"},{"name":"Distribucion","value":"Espanol"},{"name":"Uso recomendado","value":"Oficina"}]},
 {"name":"Lampara de escritorio demo","previous":"Lampara para escritorio y lectura.","description":"Lampara de escritorio para lectura y trabajo, con brazo orientable y luz LED. Su base permite apoyarla sobre una mesa. Ficha ficticia de demostracion.","specs":[{"name":"Tipo de luz","value":"LED"},{"name":"Potencia","value":"8 W"},{"name":"Color","value":"Negro"},{"name":"Uso recomendado","value":"Escritorio y lectura"}]},
 {"name":"Botella reutilizable demo","previous":"Botella reutilizable para llevar agua.","description":"Botella reutilizable para llevar agua a las actividades del dia, con tapa de rosca. Ficha ficticia de demostracion.","specs":[{"name":"Capacidad","value":"750 ml"},{"name":"Tipo de cierre","value":"Rosca"},{"name":"Uso recomendado","value":"Agua"}]}
]';

INSERT IGNORE INTO brands(name, active) VALUES ('Marca demo local', TRUE);
UPDATE products p JOIN JSON_TABLE(@fichas, '$[*]' COLUMNS(
    name VARCHAR(255) PATH '$.name', description VARCHAR(255) PATH '$.description', previous VARCHAR(255) PATH '$.previous'
)) f ON f.name = p.name
SET p.description = IF(p.description IS NULL OR p.description = '' OR p.description = f.previous, f.description, p.description),
    p.brand_id = COALESCE(p.brand_id, (SELECT id FROM brands WHERE name = 'Marca demo local'))
WHERE p.store_id = 1;

INSERT IGNORE INTO attributes(name)
SELECT DISTINCT s.name FROM JSON_TABLE(@fichas, '$[*]' COLUMNS(specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS(name VARCHAR(100) PATH '$.name')) s;

INSERT IGNORE INTO attribute_values(attribute_id, value_text)
SELECT DISTINCT a.id, s.value FROM JSON_TABLE(@fichas, '$[*]' COLUMNS(specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS(name VARCHAR(100) PATH '$.name', value VARCHAR(100) PATH '$.value')) s
JOIN attributes a ON a.name = s.name;

INSERT INTO product_attribute_values(product_id, attribute_value_id)
SELECT p.id, av.id FROM JSON_TABLE(@fichas, '$[*]' COLUMNS(name VARCHAR(255) PATH '$.name', specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS(name VARCHAR(100) PATH '$.name', value VARCHAR(100) PATH '$.value')) s
JOIN products p ON p.name = f.name AND p.store_id = 1
JOIN attributes a ON a.name = s.name
JOIN attribute_values av ON av.attribute_id = a.id AND av.value_text = s.value
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_values existing
    JOIN attribute_values v ON v.id = existing.attribute_value_id
    WHERE existing.product_id = p.id AND v.attribute_id = a.id
);
COMMIT;
