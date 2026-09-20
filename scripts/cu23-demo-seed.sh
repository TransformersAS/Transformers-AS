#!/usr/bin/env bash
# Datos de demostración de CU-23 (pedidos recibidos por el vendedor) para un MySQL local en Docker.
# SOLO desarrollo: crea dos cuentas de prueba, productos y pedidos en varios estados. Es repetible: volver a
# ejecutarlo borra los pedidos demo (transacciones demo-cu23-*) y los recrea desde cero, con stock y contraseñas
# restablecidos; los números de pedido cambian, pero los estados son siempre los mismos.
#
# Uso:
#   DEMO_PASSWORD='una-clave-local' DB_PASSWORD='la-clave-de-marketplace_app' ./scripts/cu23-demo-seed.sh
#
# Variables:
#   DEMO_PASSWORD    (obligatoria) contraseña de las dos cuentas de prueba; solo se guarda su hash bcrypt.
#   DB_PASSWORD      (obligatoria) contraseña del usuario de la aplicación en MySQL (la misma del .env).
#   MYSQL_CONTAINER  nombre del contenedor MySQL (por defecto: mysql-mkt).
#   DB_USER / DB_NAME  usuario y base de datos (por defecto: marketplace_app / marketplace).
#
# Requiere Docker y que el backend haya arrancado antes al menos una vez (Flyway crea las tablas).
set -euo pipefail

: "${DEMO_PASSWORD:?Define DEMO_PASSWORD (contraseña de las cuentas de prueba)}"
: "${DB_PASSWORD:?Define DB_PASSWORD (contraseña del usuario de la aplicación en MySQL)}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
DB_USER="${DB_USER:-marketplace_app}"
DB_NAME="${DB_NAME:-marketplace}"

SELLER_EMAIL="vendedor.demo@example.com"
BUYER_EMAIL="comprador.demo@example.com"

# Hash bcrypt (htpasswd -B genera $2y$, que Spring Security acepta). La clave nunca se escribe en un archivo.
HASH="$(docker run --rm httpd:2 htpasswd -nbBC 10 demo "$DEMO_PASSWORD" | cut -d: -f2 | tr -d '\r\n')"

# Definición de pedidos: transacción | estado | producto | cantidad | envío | horas de antigüedad
# El pedido 02 usa un producto que se borra al final: es la inconsistencia de inventario (RF-113, A3), porque el
# stock ya se descontó al pagar y solo el producto inexistente (o con stock negativo, que la BD impide) la produce.
ORDERS=(
  "demo-cu23-01|CONFIRMED|Camiseta demo|2|STANDARD|5"
  "demo-cu23-02|CONFIRMED|Taza descontinuada demo|5|EXPRESS|4"
  "demo-cu23-03|IN_PREPARATION|Libreta demo|1|STANDARD|3"
  "demo-cu23-04|READY_FOR_DISPATCH|Libreta demo|2|STANDARD|2"
  "demo-cu23-05|CONFIRMED|Camiseta demo|1|STANDARD|1"
  "demo-cu23-06|CANCELLATION_REQUESTED|Libreta demo|1|EXPRESS|1"
)

sql() {
  cat <<SQL
SET @hash = '${HASH}';

-- Reinicio de los pedidos demo (hijas primero) y de su dirección.
DELETE r FROM refunds r JOIN orders o ON o.id = r.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE c FROM order_cancellations c JOIN orders o ON o.id = c.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE i FROM order_issues i JOIN orders o ON o.id = i.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE s FROM shipments s JOIN orders o ON o.id = s.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE h FROM order_status_history h JOIN orders o ON o.id = h.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE x FROM order_items x JOIN orders o ON o.id = x.order_id WHERE o.transaction_id LIKE 'demo-cu23-%';
DELETE FROM orders WHERE transaction_id LIKE 'demo-cu23-%';
DELETE FROM addresses WHERE recipient_name = 'Ana Demo';

-- Cuentas de prueba (correo único: repetir el script solo actualiza la contraseña).
INSERT INTO user_accounts(email, password_hash, status) VALUES ('${SELLER_EMAIL}', @hash, 'ACTIVA')
  ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVA';
INSERT INTO user_accounts(email, password_hash, status) VALUES ('${BUYER_EMAIL}', @hash, 'ACTIVA')
  ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVA';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'VENDEDOR' FROM user_accounts WHERE email = '${SELLER_EMAIL}';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'COMPRADOR' FROM user_accounts WHERE email = '${BUYER_EMAIL}';

-- Tienda 1 (la crea V13) y productos: el stock se restablece en cada ejecución.
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Camiseta demo', 45000, 20, 'Moda', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Camiseta demo');
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Taza descontinuada demo', 18000, 0, 'Hogar', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Taza descontinuada demo');
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Libreta demo', 12000, 50, 'Papelería', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Libreta demo');
UPDATE products SET stock = 20 WHERE name = 'Camiseta demo';
UPDATE products SET stock = 50 WHERE name = 'Libreta demo';

-- Dirección ficticia de entrega.
INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
  SELECT 'Ana Demo', 'Calle 10 # 20-30', 'Bogotá', 'Cundinamarca', '110111', '3000000000' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM addresses WHERE recipient_name = 'Ana Demo');
SQL
  local def tx status product qty ship hours
  for def in "${ORDERS[@]}"; do
    IFS='|' read -r tx status product qty ship hours <<<"$def"
    cat <<SQL

-- Pedido ${tx} (${status})
INSERT INTO orders(account_id, status, payment_status, total, store_id, address_id, shipping_method,
                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                   delivery_postal_code, delivery_phone, transaction_id, created_at)
  SELECT b.id, '${status}', 'APPROVED', p.price * ${qty} + IF('${ship}' = 'EXPRESS', 20000, 10000), 1, a.id, '${ship}',
         a.recipient_name, a.street, a.city, a.department, a.postal_code, a.phone, '${tx}',
         NOW(6) - INTERVAL ${hours} HOUR
  FROM user_accounts b, products p, addresses a
  WHERE b.email = '${BUYER_EMAIL}' AND p.name = '${product}' AND a.recipient_name = 'Ana Demo'
    AND NOT EXISTS (SELECT 1 FROM orders WHERE transaction_id = '${tx}');
INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
  SELECT o.id, p.id, p.name, ${qty}, p.price, p.price * ${qty}
  FROM orders o, products p
  WHERE o.transaction_id = '${tx}' AND p.name = '${product}'
    AND NOT EXISTS (SELECT 1 FROM order_items WHERE order_id = o.id);
INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, correlation_id, created_at)
  SELECT o.id, NULL, o.status, 'BUYER', 'demo-seed', o.created_at
  FROM orders o
  WHERE o.transaction_id = '${tx}'
    AND NOT EXISTS (SELECT 1 FROM order_status_history WHERE order_id = o.id);
SQL
  done
  cat <<SQL

-- El producto descontinuado desaparece: el pedido 02 queda con un ítem cuyo producto ya no existe.
DELETE FROM products WHERE name = 'Taza descontinuada demo';
SQL
}

sql | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME"

echo "Listo. Cuentas de prueba (misma contraseña DEMO_PASSWORD):"
echo "  vendedor:  ${SELLER_EMAIL}"
echo "  comprador: ${BUYER_EMAIL}"
echo "Pedidos demo: ${#ORDERS[@]} (CONFIRMED x3 —uno con producto inexistente—, IN_PREPARATION, READY_FOR_DISPATCH sin envío, CANCELLATION_REQUESTED)."
