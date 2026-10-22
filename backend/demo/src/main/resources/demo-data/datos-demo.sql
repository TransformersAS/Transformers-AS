-- Datos de demostración del Marketplace. Los carga DemoDataLoader al arrancar si MARKETPLACE_DEMO_DATA=true.
--
-- Es repetible: cada inserción comprueba antes si el dato ya existe (por correo, nombre, transacción o línea de
-- pedido), así que arrancar de nuevo no duplica nada ni modifica lo que ya hay. Tampoco toca roles, contraseñas o
-- verificación de cuentas que existieran antes: solo completa lo que falta.
--
-- Contraseña de todas las cuentas: MarketplaceDemo123!  (el mismo hash que genera Spring Security con coste 12).
-- Los pedidos de demostración son de comprador.demo@example.com en la tienda principal (id 1), cuyo vendedor es
-- demo@marketplace.local. Las referencias logísticas siguen la convención del proveedor simulado (SIM-order-N/TRK-N
-- y SIM-return-N/TRK-RN), para que el webhook, la consulta periódica y HAPPY_PATH las reconozcan.

SET @hash = '$2a$12$NhgRY6a2J6kxEDi79kEvUuiW/U2bsYVfC9Ptom/PR1u7JHqrxMRC.';
SET @ahora = NOW(6);

-- ---------------------------------------------------------------------------------------------------------------
-- 1. Cuentas de cada rol, con el correo ya verificado (CU-08 no deja entrar sin verificar).
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO user_accounts (email, password_hash, status, email_verified_at)
SELECT c.email, @hash, 'ACTIVA', @ahora
FROM (SELECT 'demo@marketplace.local' AS email
      UNION ALL SELECT 'comprador.demo@example.com'
      UNION ALL SELECT 'vendedor.demo@example.com'
      UNION ALL SELECT 'soporte.demo@example.com'
      UNION ALL SELECT 'admin.demo@example.com') c
WHERE NOT EXISTS (SELECT 1 FROM user_accounts u WHERE u.email = c.email);

-- Roles solo para cuentas que aún no tienen ninguno: no se alteran cuentas existentes.
INSERT INTO user_account_roles (account_id, role)
SELECT u.id, r.role
FROM (SELECT 'demo@marketplace.local' AS email, 'COMPRADOR' AS role
      UNION ALL SELECT 'demo@marketplace.local', 'VENDEDOR'
      UNION ALL SELECT 'comprador.demo@example.com', 'COMPRADOR'
      UNION ALL SELECT 'vendedor.demo@example.com', 'VENDEDOR'
      UNION ALL SELECT 'soporte.demo@example.com', 'SOPORTE'
      UNION ALL SELECT 'admin.demo@example.com', 'ADMIN') r
JOIN user_accounts u ON u.email = r.email
WHERE NOT EXISTS (SELECT 1 FROM user_account_roles x WHERE x.account_id = u.id);

SET @vendedor_principal = (SELECT id FROM user_accounts WHERE email = 'demo@marketplace.local');
SET @comprador = (SELECT id FROM user_accounts WHERE email = 'comprador.demo@example.com');
SET @vendedor_vecino = (SELECT id FROM user_accounts WHERE email = 'vendedor.demo@example.com');

INSERT INTO seller_terms_acceptances (account_id, terms_version, accepted_at)
SELECT s.id, '2026-09', @ahora
FROM user_accounts s
WHERE s.id IN (@vendedor_principal, @vendedor_vecino)
  AND NOT EXISTS (SELECT 1 FROM seller_terms_acceptances t WHERE t.account_id = s.id);

-- ---------------------------------------------------------------------------------------------------------------
-- 2. Tiendas. La principal (V13) pasa a ser de demo@marketplace.local si aún no tiene dueña; el segundo vendedor
--    tiene su propia tienda, para poder reportar o comparar productos de otra tienda.
-- ---------------------------------------------------------------------------------------------------------------
UPDATE stores
SET owner_account_id = @vendedor_principal
WHERE id = 1 AND owner_account_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT owner_account_id FROM stores) d WHERE d.owner_account_id = @vendedor_principal);

INSERT INTO stores (name, owner_account_id, description, contact_email)
SELECT 'Tienda vendedor demo', @vendedor_vecino, 'Segunda tienda de demostración.', 'vendedor.demo@example.com'
FROM DUAL
WHERE @vendedor_vecino IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM stores s WHERE s.name = 'Tienda vendedor demo' OR s.owner_account_id = @vendedor_vecino);

SET @tienda_vecina = (SELECT id FROM stores WHERE name = 'Tienda vendedor demo');

INSERT INTO store_shipping_methods (store_id, method)
SELECT @tienda_vecina, m.method
FROM (SELECT 'STANDARD' AS method UNION ALL SELECT 'EXPRESS') m
WHERE @tienda_vecina IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM store_shipping_methods x WHERE x.store_id = @tienda_vecina AND x.method = m.method);

-- ---------------------------------------------------------------------------------------------------------------
-- 3. Catálogo: los diez productos de ejemplo de la tienda principal y tres de la segunda tienda.
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO products (name, description, price, stock, category, active, status, store_id)
SELECT c.name, c.description, c.price, c.stock, c.category, TRUE, 'ACTIVE', c.store_id
FROM (
    SELECT 'Camiseta basica demo' AS name, 'Camiseta de algodon para uso diario.' AS description, 35000 AS price,
           40 AS stock, 'Ropa' AS category, 1 AS store_id
    UNION ALL SELECT 'Jean clasico demo', 'Pantalon jean de corte clasico.', 95000, 25, 'Ropa', 1
    UNION ALL SELECT 'Chaqueta ligera demo', 'Chaqueta ligera para clima fresco.', 145000, 15, 'Ropa', 1
    UNION ALL SELECT 'Tenis urbanos demo', 'Calzado comodo para uso diario.', 120000, 30, 'Calzado', 1
    UNION ALL SELECT 'Mochila urbana demo', 'Mochila con compartimento principal amplio.', 85000, 20, 'Accesorios', 1
    UNION ALL SELECT 'Cafetera de goteo demo', 'Cafetera de goteo para preparar cafe en casa.', 75000, 18, 'Hogar', 1
    UNION ALL SELECT 'Audifonos bluetooth demo', 'Audifonos inalambricos de ejemplo.', 99000, 35, 'Tecnologia', 1
    UNION ALL SELECT 'Teclado USB demo', 'Teclado para computador con conexion USB.', 65000, 22, 'Tecnologia', 1
    UNION ALL SELECT 'Lampara de escritorio demo', 'Lampara para escritorio y lectura.', 55000, 16, 'Hogar', 1
    UNION ALL SELECT 'Botella reutilizable demo', 'Botella reutilizable para llevar agua.', 28000, 50, 'Hogar', 1
    UNION ALL SELECT 'Libreta artesanal demo', 'Libreta cosida a mano con hojas recicladas.', 18000, 30, 'Papeleria', @tienda_vecina
    UNION ALL SELECT 'Taza de ceramica demo', 'Taza de ceramica esmaltada de 350 ml.', 24000, 25, 'Hogar', @tienda_vecina
    UNION ALL SELECT 'Gorra de lona demo', 'Gorra de lona con cierre ajustable.', 32000, 20, 'Accesorios', @tienda_vecina
) c
WHERE c.store_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM products p WHERE p.store_id = c.store_id AND p.name = c.name);

-- Fichas ampliadas (CU-01): descripción, marca y especificaciones de los diez productos de la tienda principal.
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

INSERT IGNORE INTO brands (name, active) VALUES ('Marca demo local', TRUE);

UPDATE products p
JOIN JSON_TABLE(@fichas, '$[*]' COLUMNS (
    name VARCHAR(255) PATH '$.name', description VARCHAR(255) PATH '$.description', previous VARCHAR(255) PATH '$.previous'
)) f ON f.name = p.name
SET p.description = IF(p.description IS NULL OR p.description = '' OR p.description = f.previous, f.description, p.description),
    p.brand_id = COALESCE(p.brand_id, (SELECT id FROM brands WHERE name = 'Marca demo local'))
WHERE p.store_id = 1;

INSERT IGNORE INTO attributes (name)
SELECT DISTINCT s.name
FROM JSON_TABLE(@fichas, '$[*]' COLUMNS (specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS (name VARCHAR(100) PATH '$.name')) s;

INSERT IGNORE INTO attribute_values (attribute_id, value_text)
SELECT DISTINCT a.id, s.value
FROM JSON_TABLE(@fichas, '$[*]' COLUMNS (specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS (name VARCHAR(100) PATH '$.name', value VARCHAR(100) PATH '$.value')) s
JOIN attributes a ON a.name = s.name;

INSERT INTO product_attribute_values (product_id, attribute_value_id)
SELECT p.id, av.id
FROM JSON_TABLE(@fichas, '$[*]' COLUMNS (name VARCHAR(255) PATH '$.name', specs JSON PATH '$.specs')) f
CROSS JOIN JSON_TABLE(f.specs, '$[*]' COLUMNS (name VARCHAR(100) PATH '$.name', value VARCHAR(100) PATH '$.value')) s
JOIN products p ON p.name = f.name AND p.store_id = 1
JOIN attributes a ON a.name = s.name
JOIN attribute_values av ON av.attribute_id = a.id AND av.value_text = s.value
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_values existing
    JOIN attribute_values v ON v.id = existing.attribute_value_id
    WHERE existing.product_id = p.id AND v.attribute_id = a.id
);

-- ---------------------------------------------------------------------------------------------------------------
-- 4. Pedidos del comprador demo en varios estados (CU-11, CU-23, CU-24) y entregados para CU-19, CU-13 y CU-25.
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO addresses (recipient_name, street, city, department, postal_code, phone, account_id)
SELECT 'Comprador Demo', 'Calle Demo 45 # 12-30', 'Bogotá', 'Bogotá D.C.', '110111', '3001234567', @comprador
FROM DUAL
WHERE @comprador IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM addresses a WHERE a.account_id = @comprador AND a.street = 'Calle Demo 45 # 12-30');

SET @direccion = (SELECT MIN(id) FROM addresses WHERE account_id = @comprador AND street = 'Calle Demo 45 # 12-30');

-- Cada pedido avanza por la cadena de estados hasta «profundidad»: 1 confirmado, 2 en preparación, 3 listo para
-- despacho (con envío), 5 en camino, 6 entregado. Las horas son el momento de cada paso desde la compra.
DROP TEMPORARY TABLE IF EXISTS demo_pedidos;
CREATE TEMPORARY TABLE demo_pedidos (
    tx VARCHAR(40) PRIMARY KEY, producto VARCHAR(255), cantidad INT, profundidad INT, cancelado BOOLEAN, dias INT
);
INSERT INTO demo_pedidos VALUES
    ('demo-datos-01', 'Camiseta basica demo',       1, 1, FALSE, 1),
    ('demo-datos-02', 'Jean clasico demo',          1, 2, FALSE, 1),
    ('demo-datos-03', 'Mochila urbana demo',        1, 3, FALSE, 1),
    ('demo-datos-04', 'Tenis urbanos demo',         1, 5, FALSE, 2),
    ('demo-datos-05', 'Audifonos bluetooth demo',   1, 6, FALSE, 5),
    ('demo-datos-06', 'Cafetera de goteo demo',     1, 6, FALSE, 5),
    ('demo-datos-07', 'Teclado USB demo',           1, 6, FALSE, 5),
    ('demo-datos-08', 'Lampara de escritorio demo', 1, 6, FALSE, 5),
    ('demo-datos-09', 'Botella reutilizable demo',  2, 1, TRUE,  3);

DROP TEMPORARY TABLE IF EXISTS demo_cadena;
CREATE TEMPORARY TABLE demo_cadena (
    paso INT PRIMARY KEY, desde VARCHAR(32), hacia VARCHAR(32), actor VARCHAR(16), horas INT
);
INSERT INTO demo_cadena VALUES
    (1, NULL,                 'CONFIRMED',          'SYSTEM',     0),
    (2, 'CONFIRMED',          'IN_PREPARATION',     'SELLER',     2),
    (3, 'IN_PREPARATION',     'READY_FOR_DISPATCH', 'SELLER',     4),
    (4, 'READY_FOR_DISPATCH', 'PICKED_UP',          'LOGISTICS', 24),
    (5, 'PICKED_UP',          'IN_TRANSIT',         'LOGISTICS', 30),
    (6, 'IN_TRANSIT',         'DELIVERED',          'LOGISTICS', 48);

INSERT INTO orders (status, total, address_id, shipping_method, transaction_id, created_at, account_id, store_id,
                    payment_status, delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                    delivery_postal_code, delivery_phone)
SELECT IF(d.cancelado, 'CANCELLED', (SELECT c.hacia FROM demo_cadena c WHERE c.paso = d.profundidad)),
       p.price * d.cantidad + 10000, a.id, 'STANDARD', d.tx, @ahora - INTERVAL d.dias DAY, @comprador, 1,
       IF(d.cancelado, 'REFUNDED', 'APPROVED'),
       a.recipient_name, a.street, a.city, a.department, a.postal_code, a.phone
FROM demo_pedidos d
JOIN products p ON p.store_id = 1 AND p.name = d.producto
JOIN addresses a ON a.id = @direccion
WHERE @comprador IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.transaction_id = d.tx);

INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, subtotal)
SELECT o.id, p.id, p.name, d.cantidad, p.price, p.price * d.cantidad
FROM demo_pedidos d
JOIN orders o ON o.transaction_id = d.tx
JOIN products p ON p.store_id = 1 AND p.name = d.producto
WHERE NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id);

INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, correlation_id, created_at)
SELECT o.id, c.desde, c.hacia, c.actor,
       CASE c.actor WHEN 'SELLER' THEN @vendedor_principal ELSE NULL END,
       NULL, 'demo-datos', o.created_at + INTERVAL c.horas HOUR
FROM demo_pedidos d
JOIN orders o ON o.transaction_id = d.tx
JOIN demo_cadena c ON c.paso <= IF(d.cancelado, 1, d.profundidad)
WHERE NOT EXISTS (SELECT 1 FROM order_status_history h WHERE h.order_id = o.id);

-- MySQL no deja leer una tabla temporal dos veces en la misma consulta: la cancelación va en una inserción aparte.
INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, correlation_id, created_at)
SELECT o.id, 'CONFIRMED', 'CANCELLED', 'SELLER', @vendedor_principal, 'OUT_OF_STOCK', 'demo-datos',
       o.created_at + INTERVAL 3 HOUR
FROM demo_pedidos d
JOIN orders o ON o.transaction_id = d.tx
WHERE d.cancelado
  AND NOT EXISTS (SELECT 1 FROM order_status_history h WHERE h.order_id = o.id AND h.to_status = 'CANCELLED');

-- Envío creado al despachar (paso 3) y novedades del proveedor para los que ya salieron (pasos 4 a 6).
INSERT INTO shipments (order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at, tracking_active)
SELECT o.id, CONCAT('SIM-order-', o.id), CONCAT('TRK-', o.id), CONCAT('order-', o.id), 'CREATED',
       o.created_at + INTERVAL 4 HOUR, d.profundidad < 6
FROM demo_pedidos d
JOIN orders o ON o.transaction_id = d.tx
WHERE NOT d.cancelado AND d.profundidad >= 3
  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id);

INSERT INTO shipment_tracking_events (shipment_id, order_id, provider_event_id, event_type, occurred_at, received_at,
                                      source, outcome, description, location, evidence_type, evidence_reference,
                                      correlation_id)
SELECT s.id, o.id, CONCAT('sim-SIM-order-', o.id, '-', c.hacia), c.hacia,
       o.created_at + INTERVAL c.horas HOUR, o.created_at + INTERVAL c.horas HOUR + INTERVAL 1 MINUTE,
       'POLLING', 'APPLIED', 'Simulador logístico', 'Bogotá',
       IF(c.hacia = 'DELIVERED', 'CODE', NULL), IF(c.hacia = 'DELIVERED', CONCAT('SIM-POD-TRK-', o.id), NULL),
       'demo-datos'
FROM demo_pedidos d
JOIN orders o ON o.transaction_id = d.tx
JOIN shipments s ON s.order_id = o.id
JOIN demo_cadena c ON c.paso BETWEEN 4 AND d.profundidad
WHERE NOT EXISTS (SELECT 1 FROM shipment_tracking_events e WHERE e.shipment_id = s.id
                  AND e.provider_event_id = CONCAT('sim-SIM-order-', o.id, '-', c.hacia));

-- ---------------------------------------------------------------------------------------------------------------
-- 5. Devoluciones (CU-19 y CU-25). demo-datos-05 queda libre para solicitar una devolución en vivo.
--    demo-datos-06: aprobada, con recogida elegida y su retorno registrado en logística, listo para seguirlo.
--    demo-datos-07: recién solicitada, para que la tienda la revise.
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO return_requests (order_id, order_item_id, product_id, product_name, quantity, unit_price, refund_amount,
                             buyer_account_id, store_id, status, reason_code, description, origin, return_window_days,
                             delivered_at, decision_note, decided_by_account_id, decided_at, return_method_code,
                             method_chosen_at, created_at, updated_at)
SELECT o.id, i.id, i.product_id, i.product_name, i.quantity, i.unit_price, i.subtotal, o.account_id, o.store_id,
       r.estado, r.motivo, r.descripcion, 'BUYER', 30, o.created_at + INTERVAL 48 HOUR,
       IF(r.estado = 'APPROVED', 'Aprobada: la revisaremos cuando llegue.', NULL),
       IF(r.estado = 'APPROVED', @vendedor_principal, NULL),
       IF(r.estado = 'APPROVED', @ahora - INTERVAL 36 HOUR, NULL),
       IF(r.estado = 'APPROVED', 'PICKUP', NULL),
       IF(r.estado = 'APPROVED', @ahora - INTERVAL 30 HOUR, NULL),
       @ahora - INTERVAL 48 HOUR, IF(r.estado = 'APPROVED', @ahora - INTERVAL 30 HOUR, @ahora - INTERVAL 48 HOUR)
FROM (SELECT 'demo-datos-06' AS tx, 'APPROVED' AS estado, 'DEFECTIVE' AS motivo,
             'La cafetera no enciende desde el primer uso.' AS descripcion
      UNION ALL SELECT 'demo-datos-07', 'REQUESTED', 'NOT_AS_DESCRIBED',
             'El teclado llegó con distribución en inglés y no en español.') r
JOIN orders o ON o.transaction_id = r.tx
JOIN order_items i ON i.order_id = o.id
WHERE NOT EXISTS (SELECT 1 FROM return_requests x WHERE x.order_item_id = i.id);

-- Línea de tiempo de cada devolución, igual a la que escribe CU-19 en cada paso.
INSERT INTO return_events (return_id, event_type, from_status, to_status, actor_type, actor_id, details,
                           correlation_id, created_at)
SELECT rr.id, e.tipo, e.desde, e.hacia, e.actor, IF(e.actor = 'BUYER', rr.buyer_account_id, @vendedor_principal),
       IF(e.tipo = 'APPROVED', rr.decision_note, IF(e.tipo = 'METHOD_CHOSEN', rr.return_method_code, NULL)),
       'demo-datos', @ahora - INTERVAL e.horas HOUR
FROM return_requests rr
JOIN orders o ON o.id = rr.order_id AND o.transaction_id IN ('demo-datos-06', 'demo-datos-07')
JOIN (SELECT 'REQUESTED' AS tipo, NULL AS desde, 'REQUESTED' AS hacia, 'BUYER' AS actor, 48 AS horas, 1 AS orden
      UNION ALL SELECT 'REVIEW_STARTED', 'REQUESTED', 'IN_REVIEW', 'SELLER', 40, 2
      UNION ALL SELECT 'APPROVED', 'IN_REVIEW', 'APPROVED', 'SELLER', 36, 3
      UNION ALL SELECT 'METHOD_CHOSEN', NULL, NULL, 'BUYER', 30, 4) e
  ON rr.status = 'APPROVED' OR e.orden = 1
WHERE NOT EXISTS (SELECT 1 FROM return_events x WHERE x.return_id = rr.id);

-- Retorno registrado en logística (lo que hace CU-19 al elegir el método, mediante RegisterReturnShipmentUseCase).
INSERT INTO return_shipments (return_id, buyer_account_id, store_id, provider_return_id, tracking_code, status,
                              failed_pickups, pickup_stopped, tracking_active, created_at, updated_at)
SELECT rr.id, rr.buyer_account_id, rr.store_id, CONCAT('SIM-return-', rr.id), CONCAT('TRK-R', rr.id),
       'PICKUP_PENDING', 0, FALSE, TRUE, @ahora - INTERVAL 30 HOUR, @ahora - INTERVAL 30 HOUR
FROM return_requests rr
JOIN orders o ON o.id = rr.order_id AND o.transaction_id = 'demo-datos-06'
WHERE rr.return_method_code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM return_shipments x WHERE x.return_id = rr.id);

INSERT INTO return_tracking_events (return_shipment_id, provider_event_id, event_type, occurred_at, received_at,
                                    source, outcome, description, correlation_id)
SELECT rs.id, CONCAT('registered-', rs.return_id), 'PICKUP_SCHEDULED', rs.created_at, rs.created_at, 'SYSTEM',
       'APPLIED', 'Devolución registrada en logística', 'demo-datos'
FROM return_shipments rs
JOIN return_requests rr ON rr.id = rs.return_id
JOIN orders o ON o.id = rr.order_id AND o.transaction_id = 'demo-datos-06'
WHERE NOT EXISTS (SELECT 1 FROM return_tracking_events x WHERE x.return_shipment_id = rs.id
                  AND x.provider_event_id = CONCAT('registered-', rs.return_id));

-- ---------------------------------------------------------------------------------------------------------------
-- 6. Reclamación abierta (CU-13) sobre demo-datos-08, para que la tienda la atienda o el comprador la escale.
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO claims (order_id, product_id, product_name, item_total, buyer_account_id, store_id, description, status,
                    created_at, updated_at)
SELECT o.id, i.product_id, i.product_name, i.subtotal, o.account_id, o.store_id,
       'La lámpara parpadea al encenderla y a veces se apaga sola.', 'OPEN',
       @ahora - INTERVAL 20 HOUR, @ahora - INTERVAL 20 HOUR
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.transaction_id = 'demo-datos-08'
  AND NOT EXISTS (SELECT 1 FROM claims c WHERE c.order_id = o.id AND c.product_id = i.product_id);

DROP TEMPORARY TABLE IF EXISTS demo_pedidos;
DROP TEMPORARY TABLE IF EXISTS demo_cadena;
