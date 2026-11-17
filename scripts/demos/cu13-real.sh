#!/usr/bin/env bash
# CU-13 contra la aplicación real: una reclamación pasa por la compradora, el vendedor de la tienda 1 y soporte hasta
# el reembolso, y se muestra lo que quedó en MySQL.
# Uso (raíz del repo, Docker abierto, Git Bash):  bash scripts/demos/cu13-real.sh
#
# Antes de abrir Chrome prepara en la base lo que la demo necesita y no se puede hacer desde la pantalla: una compra ya
# entregada (el pago real solo es por Mercado Pago, que es externo) y las cuentas de la compradora y de soporte. Las
# cuentas usan la contraseña pública de la cuenta de demostración demo@marketplace.local.
source "$(dirname "$0")/real-comun.sh"
asegurar_app

export DEMO_COMPRADORA="cu13.compradora@demo.local"
export DEMO_SOPORTE="cu13.soporte@demo.local"
export DEMO_PRODUCTO_NOMBRE="Lampara de escritorio demo"
TRANSACCION="DEMO-CU13-$(date +%Y%m%d)-$SELLO"

sql "
SET @clave := (SELECT password_hash FROM user_accounts WHERE email = 'demo@marketplace.local');
INSERT INTO user_accounts(email, password_hash, status, email_verified_at, communications_restricted)
SELECT c.email, @clave, 'ACTIVA', NOW(6), 0
FROM (SELECT '$DEMO_COMPRADORA' AS email UNION ALL SELECT '$DEMO_SOPORTE') c
WHERE NOT EXISTS (SELECT 1 FROM user_accounts u WHERE u.email = c.email);
INSERT IGNORE INTO user_account_roles(account_id, role)
SELECT id, 'COMPRADOR' FROM user_accounts WHERE email = '$DEMO_COMPRADORA';
INSERT IGNORE INTO user_account_roles(account_id, role)
SELECT id, 'SOPORTE' FROM user_accounts WHERE email = '$DEMO_SOPORTE';

SET @compradora := (SELECT id FROM user_accounts WHERE email = '$DEMO_COMPRADORA');
SET @producto := (SELECT id FROM products WHERE store_id = 1 AND name = '$DEMO_PRODUCTO_NOMBRE' LIMIT 1);
SET @precio := (SELECT price FROM products WHERE id = @producto);
INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone, account_id, deleted, is_default)
VALUES ('Lucía Compradora', 'Calle 45 # 12-30', 'Bogotá', 'Bogotá D.C.', '110111', '+57 300 765 4321', @compradora, 0, 0);
SET @direccion := LAST_INSERT_ID();
INSERT INTO orders(status, total, address_id, shipping_method, transaction_id, created_at, account_id, store_id,
                   payment_status, delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                   delivery_postal_code, delivery_phone, discount_amount, shipping_cost, seller_name_snapshot)
VALUES ('DELIVERED', @precio + 10000, @direccion, 'STANDARD', '$TRANSACCION', NOW(6) - INTERVAL 3 DAY, @compradora, 1,
        'APPROVED', 'Lucía Compradora', 'Calle 45 # 12-30', 'Bogotá', 'Bogotá D.C.', '110111', '+57 300 765 4321',
        0, 10000, (SELECT name FROM stores WHERE id = 1));
SET @pedido := LAST_INSERT_ID();
INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
VALUES (@pedido, @producto, '$DEMO_PRODUCTO_NOMBRE', 1, @precio, @precio);
INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, reason, correlation_id, created_at)
VALUES (@pedido, NULL, 'DELIVERED', 'BUYER', 'Compra entregada para la demostración de CU-13', 'demo-cu13', NOW(6));
" >/dev/null || { echo "No se pudo preparar la compra de la demostración." >&2; exit 1; }

export DEMO_PEDIDO=$(valor "SELECT id FROM orders WHERE transaction_id = '$TRANSACCION';" | tr -d '\r')
export DEMO_PAGADO=$(valor "SELECT CAST(subtotal AS UNSIGNED) FROM order_items WHERE order_id = $DEMO_PEDIDO;" | tr -d '\r')
[[ -n "$DEMO_PEDIDO" ]] || { echo "No se encontró la compra preparada." >&2; exit 1; }
echo "== CU-13 contra $URL · compra entregada #$DEMO_PEDIDO de $DEMO_COMPRADORA =="
correr reclamaciones-real.spec.ts

RECLAMACION=$(dato RECLAMACION)
echo "-- Tabla claims: la reclamación, su estado final y el reembolso"
sql "SELECT id, order_id, product_name, item_total, status, proposed_refund, resolution, refund_amount, refund_status
     FROM claims WHERE id = $RECLAMACION;"
echo "-- Tabla claim_messages: el historial (quién escribió y qué hizo)"
sql "SELECT position, author, kind, LEFT(message, 60) AS mensaje FROM claim_messages
     WHERE claim_id = $RECLAMACION ORDER BY position;"
echo "-- Tabla claim_evidences: la evidencia adjunta"
sql "SELECT * FROM claim_evidences WHERE claim_id = $RECLAMACION;"
echo "-- Tabla refunds: el reembolso registrado para la compra"
sql "SELECT id, order_id, amount, idempotency_key, status, created_at FROM refunds WHERE order_id = $DEMO_PEDIDO;"
