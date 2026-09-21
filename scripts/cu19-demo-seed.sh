#!/usr/bin/env bash
# Datos de demostración de CU-19 (devolución de compra) para un MySQL local en Docker.
# SOLO desarrollo: crea las cuentas y los pedidos entregados que hacen falta para probar cada alternativa.
#
#   comprador.demo@example.com COMPRADOR compradora de todos los pedidos de abajo
#   vendedor.demo@example.com  VENDEDOR  dueña de la tienda 1 solo si esa tienda aún no tenía dueña. Con el perfil
#                                        `local` la tienda 1 ya es de demo@marketplace.local: para actuar como vendedor
#                                        entra con esa cuenta (MarketplaceDemo123!) y elige el rol Vendedor
#
# Pedidos (transacciones demo-cu19-*), todos de la tienda 1, una línea de 2 unidades a 10.000 cada una:
#   demo-cu19-01  ENTREGADO hace DELIVERED_DAYS_AGO días (3 por defecto)   -> elegible
#   demo-cu19-02  ENTREGADO hace 45 días                                   -> fuera del plazo de 30 días (A1)
#   demo-cu19-03  ENTREGADO sin ninguna fecha de entrega registrada        -> no elegible: fecha desconocida (A1)
#   demo-cu19-04  CONFIRMADO                                               -> no elegible: no está entregado (A1)
#   demo-cu19-05  ENTREGADO hace 3 días                                    -> para el flujo de rechazo y reclamación
#
# La fecha de entrega sale de un evento DELIVERED de seguimiento logístico (shipment_tracking_events, resultado APPLIED)
# con occurred_at controlable, que es la fuente que usa devoluciones; el historial del pedido queda una hora después,
# como pasaría con un webhook. Con DELIVERED_AT='2026-09-01 10:00:00' se fija la fecha exacta del pedido 01.
#
# Es repetible: volver a ejecutarlo borra los pedidos demo y todo lo que colgaba de ellos (devoluciones, reembolsos,
# reclamaciones) y los recrea, con las contraseñas restablecidas.
#
# Uso:
#   DEMO_PASSWORD='una-clave-local' DB_PASSWORD='la-clave-de-marketplace_app' ./scripts/cu19-demo-seed.sh
#   DELIVERED_DAYS_AGO=29 ...   # el pedido 01 vence mañana: probar el último día del plazo
#
# Variables: DEMO_PASSWORD y DB_PASSWORD (obligatorias), DELIVERED_DAYS_AGO (3), DELIVERED_AT (vacía),
# MYSQL_CONTAINER (mysql-mkt), DB_USER (marketplace_app), DB_NAME (marketplace). Requiere Docker y que el backend haya
# arrancado antes al menos una vez (Flyway crea las tablas).
set -euo pipefail

: "${DEMO_PASSWORD:?Define DEMO_PASSWORD (contraseña de las cuentas de prueba)}"
: "${DB_PASSWORD:?Define DB_PASSWORD (contraseña del usuario de la aplicación en MySQL)}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
DB_USER="${DB_USER:-marketplace_app}"
DB_NAME="${DB_NAME:-marketplace}"
DELIVERED_DAYS_AGO="${DELIVERED_DAYS_AGO:-3}"
DELIVERED_AT="${DELIVERED_AT:-}"

case "$DELIVERED_DAYS_AGO" in ''|*[!0-9]*) echo "DELIVERED_DAYS_AGO debe ser un número entero de días" >&2; exit 1;; esac
DATE_PATTERN='^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}$'
if [ -n "$DELIVERED_AT" ] && ! [[ "$DELIVERED_AT" =~ $DATE_PATTERN ]]; then
  echo "DELIVERED_AT debe tener la forma 'AAAA-MM-DD HH:MM:SS'" >&2
  exit 1
fi

# Hash bcrypt (htpasswd -B genera $2y$, que Spring Security acepta). La clave nunca se escribe en un archivo.
HASH="$(docker run --rm httpd:2 htpasswd -nbBC 10 demo "$DEMO_PASSWORD" | cut -d: -f2 | tr -d '\r\n')"

# Cuándo se entregó el pedido 01: fecha exacta o "hace N días".
if [ -n "$DELIVERED_AT" ]; then
  FIRST_DELIVERY="'${DELIVERED_AT/T/ }'"
else
  FIRST_DELIVERY="NOW(6) - INTERVAL ${DELIVERED_DAYS_AGO} DAY"
fi

# Definición: transacción | estado del pedido | entrega (SQL) o NONE | ¿evento de seguimiento? (EVENT | HISTORY | NONE)
ORDERS=(
  "demo-cu19-01|DELIVERED|${FIRST_DELIVERY}|EVENT"
  "demo-cu19-02|DELIVERED|NOW(6) - INTERVAL 45 DAY|EVENT"
  "demo-cu19-03|DELIVERED|NONE|NONE"
  "demo-cu19-04|CONFIRMED|NONE|NONE"
  "demo-cu19-05|DELIVERED|NOW(6) - INTERVAL 3 DAY|EVENT"
)

sql() {
  cat <<SQL
SET @hash = '${HASH}';

-- Reinicio de los pedidos demo y de todo lo que colgaba de ellos (hijas primero).
DELETE f FROM return_evidence_files f JOIN return_requests r ON r.id = f.return_id JOIN orders o ON o.id = r.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE e FROM return_events e JOIN return_requests r ON r.id = e.return_id JOIN orders o ON o.id = r.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE i FROM return_information_requests i JOIN return_requests r ON r.id = i.return_id JOIN orders o ON o.id = r.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE r FROM return_requests r JOIN orders o ON o.id = r.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE m FROM claim_messages m JOIN claims c ON c.id = m.claim_id JOIN orders o ON o.id = c.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE ce FROM claim_evidences ce JOIN claims c ON c.id = ce.claim_id JOIN orders o ON o.id = c.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE c FROM claims c JOIN orders o ON o.id = c.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE rf FROM refunds rf JOIN orders o ON o.id = rf.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE t FROM shipment_tracking_events t JOIN orders o ON o.id = t.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE s FROM shipments s JOIN orders o ON o.id = s.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE h FROM order_status_history h JOIN orders o ON o.id = h.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE x FROM order_items x JOIN orders o ON o.id = x.order_id WHERE o.transaction_id LIKE 'demo-cu19-%';
DELETE FROM orders WHERE transaction_id LIKE 'demo-cu19-%';
DELETE FROM addresses WHERE recipient_name = 'Ana Devolución';

-- Cuentas de prueba (correo único: repetir el script solo actualiza la contraseña).
INSERT INTO user_accounts(email, password_hash, status) VALUES
  ('comprador.demo@example.com', @hash, 'ACTIVA'), ('vendedor.demo@example.com', @hash, 'ACTIVA')
  ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVA';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'COMPRADOR' FROM user_accounts WHERE email = 'comprador.demo@example.com';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'VENDEDOR' FROM user_accounts WHERE email = 'vendedor.demo@example.com';
UPDATE stores SET owner_account_id = (SELECT id FROM user_accounts WHERE email = 'vendedor.demo@example.com')
  WHERE id = 1 AND owner_account_id IS NULL
    AND (SELECT id FROM user_accounts WHERE email = 'vendedor.demo@example.com') NOT IN
        (SELECT owner_account_id FROM (SELECT owner_account_id FROM stores WHERE owner_account_id IS NOT NULL) AS o);

-- Producto de la tienda 1 y dirección ficticia de entrega.
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Lámpara demo devolución', 10000, 20, 'Hogar', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Lámpara demo devolución');
INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
  VALUES ('Ana Devolución', 'Calle 10 # 20-30', 'Bogotá', 'Cundinamarca', '110111', '3000000000');
SQL
  local def tx status delivery tracking
  for def in "${ORDERS[@]}"; do
    IFS='|' read -r tx status delivery tracking <<<"$def"
    cat <<SQL

-- Pedido ${tx} (${status}, entrega: ${delivery})
INSERT INTO orders(account_id, status, payment_status, total, store_id, address_id, shipping_method,
                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                   delivery_postal_code, delivery_phone, transaction_id, created_at)
  SELECT b.id, '${status}', 'APPROVED', p.price * 2, 1, a.id, 'STANDARD',
         a.recipient_name, a.street, a.city, a.department, a.postal_code, a.phone, '${tx}',
         NOW(6) - INTERVAL 60 DAY
  FROM user_accounts b, products p, addresses a
  WHERE b.email = 'comprador.demo@example.com' AND p.name = 'Lámpara demo devolución'
    AND a.recipient_name = 'Ana Devolución';
INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
  SELECT o.id, p.id, p.name, 2, p.price, p.price * 2
  FROM orders o, products p WHERE o.transaction_id = '${tx}' AND p.name = 'Lámpara demo devolución';
INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, correlation_id, created_at)
  SELECT o.id, NULL, 'CONFIRMED', 'BUYER', 'demo-seed', o.created_at FROM orders o WHERE o.transaction_id = '${tx}';
SQL
    if [ "$tracking" = "EVENT" ]; then
      cat <<SQL
INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
  SELECT o.id, CONCAT('prov-', o.transaction_id), CONCAT('TRK-', o.transaction_id), CONCAT('order-', o.id),
         'DELIVERED', o.created_at FROM orders o WHERE o.transaction_id = '${tx}';
-- La fecha real de entrega, tal como la reporta el proveedor: es la que usa devoluciones.
INSERT INTO shipment_tracking_events(shipment_id, order_id, provider_event_id, event_type, occurred_at, received_at,
                                     source, outcome, description, correlation_id)
  SELECT s.id, o.id, 'demo-delivered', 'DELIVERED', ${delivery}, ${delivery} + INTERVAL 1 HOUR, 'WEBHOOK', 'APPLIED',
         'Entregado al comprador', 'demo-seed'
  FROM orders o JOIN shipments s ON s.order_id = o.id WHERE o.transaction_id = '${tx}';
INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, correlation_id, created_at)
  SELECT o.id, 'IN_TRANSIT', 'DELIVERED', 'LOGISTICS', 'demo-seed', ${delivery} + INTERVAL 1 HOUR
  FROM orders o WHERE o.transaction_id = '${tx}';
SQL
    fi
  done
}

sql | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME"

echo "Listo. Cuentas de prueba (misma contraseña DEMO_PASSWORD):"
echo "  comprador: comprador.demo@example.com"
echo "  vendedor:  vendedor.demo@example.com (dueña de la tienda 1 solo si no tenía dueña; con el perfil local, demo@marketplace.local)"
if [ -n "$DELIVERED_AT" ]; then
  echo "Pedido demo-cu19-01: entregado el ${DELIVERED_AT}."
else
  echo "Pedido demo-cu19-01: entregado hace ${DELIVERED_DAYS_AGO} días."
fi
echo "Pedidos demo: ${#ORDERS[@]} (01 elegible, 02 fuera de plazo, 03 sin fecha de entrega, 04 no entregado, 05 para rechazo y reclamación)."
exit 0
