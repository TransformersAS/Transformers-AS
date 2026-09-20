#!/usr/bin/env bash
# Datos de demostración de CU-24 (seguimiento logístico de pedidos) y CU-25 (seguimiento de devoluciones) para un
# MySQL local en Docker. SOLO desarrollo. Es repetible: volver a ejecutarlo borra lo que creó antes (transacciones
# demo-cu24-* y devoluciones 9001 a 9003) y lo recrea desde cero.
#
# Crea 3 pedidos «Listo para despacho» con su envío ya creado (SIM-order-N / TRK-N) para el comprador demo y 3
# devoluciones aprobadas (9001, 9002, 9003) en «Recogida pendiente» con su referencia logística (SIM-return-N / TRK-RN).
# Las devoluciones las crearía CU-19; mientras no exista, este script hace de CU-19 para poder probar CU-25.
#
# Uso (después de scripts/cu23-demo-seed.sh, que crea las cuentas):
#   DB_PASSWORD='la-clave-de-marketplace_app' MYSQL_CONTAINER=transformers-as-mysql-1 ./scripts/cu24-25-demo-seed.sh
#
# Variables:
#   DB_PASSWORD      (obligatoria) contraseña del usuario de la aplicación en MySQL (la misma del .env).
#   MYSQL_CONTAINER  nombre del contenedor MySQL (por defecto: mysql-mkt; con docker compose: transformers-as-mysql-1).
#   BUYER_EMAIL      comprador dueño de los pedidos y devoluciones (por defecto: comprador.demo@example.com).
#   DB_USER / DB_NAME  usuario y base de datos (por defecto: marketplace_app / marketplace).
set -euo pipefail

: "${DB_PASSWORD:?Define DB_PASSWORD (contraseña del usuario de la aplicación en MySQL)}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
DB_USER="${DB_USER:-marketplace_app}"
DB_NAME="${DB_NAME:-marketplace}"
BUYER_EMAIL="${BUYER_EMAIL:-comprador.demo@example.com}"

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME" "$@"
}

sql() {
  cat <<SQL
-- Reinicio de lo que creó una ejecución anterior (hijas primero).
DELETE e FROM shipment_tracking_events e JOIN orders o ON o.id = e.order_id WHERE o.transaction_id LIKE 'demo-cu24-%';
DELETE s FROM shipments s JOIN orders o ON o.id = s.order_id WHERE o.transaction_id LIKE 'demo-cu24-%';
DELETE h FROM order_status_history h JOIN orders o ON o.id = h.order_id WHERE o.transaction_id LIKE 'demo-cu24-%';
DELETE x FROM order_items x JOIN orders o ON o.id = x.order_id WHERE o.transaction_id LIKE 'demo-cu24-%';
DELETE FROM orders WHERE transaction_id LIKE 'demo-cu24-%';
DELETE e FROM return_tracking_events e JOIN return_shipments r ON r.id = e.return_shipment_id WHERE r.return_id IN (9001, 9002, 9003);
DELETE FROM return_shipments WHERE return_id IN (9001, 9002, 9003);
DELETE FROM addresses WHERE recipient_name = 'Ana Seguimiento';
SQL
  cat <<SQL

INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Libreta demo', 12000, 50, 'Papelería', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Libreta demo');
INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
  VALUES ('Ana Seguimiento', 'Calle 10 # 20-30', 'Bogotá', 'Cundinamarca', '110111', '3000000000');
SQL
  local n
  for n in 1 2 3; do
    cat <<SQL

-- Pedido demo-cu24-0${n}: listo para despacho, con su envío ya creado en el proveedor logístico simulado.
INSERT INTO orders(account_id, status, payment_status, total, store_id, address_id, shipping_method,
                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                   delivery_postal_code, delivery_phone, transaction_id, created_at)
  SELECT b.id, 'READY_FOR_DISPATCH', 'APPROVED', p.price + 10000, 1, a.id, 'STANDARD', a.recipient_name, a.street,
         a.city, a.department, a.postal_code, a.phone, 'demo-cu24-0${n}', NOW(6) - INTERVAL 2 HOUR
  FROM user_accounts b, products p, addresses a
  WHERE b.email = '${BUYER_EMAIL}' AND p.name = 'Libreta demo' AND a.recipient_name = 'Ana Seguimiento';
INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
  SELECT o.id, p.id, p.name, 1, p.price, p.price FROM orders o, products p
  WHERE o.transaction_id = 'demo-cu24-0${n}' AND p.name = 'Libreta demo';
INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, correlation_id, created_at)
  SELECT id, NULL, 'READY_FOR_DISPATCH', 'SYSTEM', 'demo-seed', created_at FROM orders WHERE transaction_id = 'demo-cu24-0${n}';
INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
  SELECT id, CONCAT('SIM-order-', id), CONCAT('TRK-', id), CONCAT('order-', id), 'CREATED', NOW(6)
  FROM orders WHERE transaction_id = 'demo-cu24-0${n}';
SQL
  done
  for n in 9001 9002 9003; do
    cat <<SQL

-- Devolución ${n} aprobada (lo que haría CU-19): recogida pendiente con su referencia logística.
INSERT INTO return_shipments(return_id, buyer_account_id, store_id, provider_return_id, tracking_code, status,
                             failed_pickups, pickup_stopped, created_at, updated_at)
  SELECT ${n}, b.id, 1, 'SIM-return-${n}', 'TRK-R${n}', 'PICKUP_PENDING', 0, FALSE, NOW(6), NOW(6)
  FROM user_accounts b WHERE b.email = '${BUYER_EMAIL}';
INSERT INTO return_tracking_events(return_shipment_id, provider_event_id, event_type, occurred_at, received_at, source,
                                   outcome, description, correlation_id)
  SELECT id, 'registered-${n}', 'PICKUP_SCHEDULED', NOW(6), NOW(6), 'SYSTEM', 'APPLIED',
         'Devolución registrada en logística', 'demo-seed'
  FROM return_shipments WHERE return_id = ${n};
SQL
  done
}

sql | mysql_exec

echo "Listo. Pedidos demo listos para despacho (comprador ${BUYER_EMAIL}):"
echo "SELECT o.id AS pedido, s.provider_shipment_id AS envio, s.tracking_code AS guia FROM orders o JOIN shipments s ON s.order_id = o.id WHERE o.transaction_id LIKE 'demo-cu24-%' ORDER BY o.id;" | mysql_exec
echo "Devoluciones demo: 9001, 9002 y 9003 (SIM-return-N / TRK-RN)."
