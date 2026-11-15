#!/usr/bin/env bash
# Datos de demostración de CU-22 (restricciones de cuentas) para un MySQL local en Docker.
# SOLO desarrollo. Parte de los datos demo del backend (MARKETPLACE_DEMO_DATA=true), que ya crean las cuentas con la
# contraseña MarketplaceDemo123!:
#
#   admin.demo@example.com   ADMIN                 busca la cuenta, revisa antecedentes y la restringe
#   dueña de la tienda 1     COMPRADOR y VENDEDOR  la cuenta evaluada (con los datos demo, demo@marketplace.local)
#
# Le agrega a la tienda 1 los dos antecedentes confirmados que CU-22 muestra, así la evaluación sale como
# «Reincidente» en vez de vacía:
#   - una reclamación resuelta por soporte con reembolso (sobre el pedido entregado demo-datos-05)
#   - una decisión de moderación RETIRAR sobre «Publicación retirada demo CU-22» (producto inactivo, propio del
#     script, para no ocultar nada del catálogo demo)
#
# Es repetible: no duplica nada. Con RESET_RESTRICTIONS=1 además borra todas las restricciones y solicitudes de
# información de CU-22 y devuelve a ACTIVE las tiendas que estaban RESTRICTED, para repetir la demo desde cero.
#
# Uso:
#   DB_PASSWORD='la-clave-de-marketplace_app' ./scripts/cu22-demo-seed.sh
#
# Variables: DB_PASSWORD (obligatoria), MYSQL_CONTAINER (mysql-mkt), DB_USER (marketplace_app), DB_NAME (marketplace),
# RESET_RESTRICTIONS (0). Requiere Docker y que el backend haya arrancado antes con los datos demo.
set -euo pipefail

: "${DB_PASSWORD:?Define DB_PASSWORD (contraseña del usuario de la aplicación en MySQL)}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
DB_USER="${DB_USER:-marketplace_app}"
DB_NAME="${DB_NAME:-marketplace}"
RESET_RESTRICTIONS="${RESET_RESTRICTIONS:-0}"

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME" "$@"
}

OWNER="$(mysql_exec -N -e "SELECT a.email FROM stores s JOIN user_accounts a ON a.id = s.owner_account_id WHERE s.id = 1")"
DEMO_ORDER="$(mysql_exec -N -e "SELECT COUNT(*) FROM orders WHERE transaction_id = 'demo-datos-05' AND store_id = 1")"
if [ -z "$OWNER" ] || [ "$DEMO_ORDER" != "1" ]; then
  echo "Faltan los datos demo: la tienda 1 no tiene dueña o no existe el pedido demo-datos-05." >&2
  echo "Arranca el backend una vez con MARKETPLACE_DEMO_DATA=true y vuelve a ejecutar este script." >&2
  exit 1
fi

sql() {
  cat <<'SQL'
SET @ahora = NOW(6);

-- Antecedente 1: reclamación del pedido entregado demo-datos-05 que soporte resolvió con reembolso.
INSERT INTO claims (order_id, product_id, product_name, item_total, buyer_account_id, store_id, description, status,
                    resolution, resolution_note, refund_amount, created_at, updated_at)
SELECT o.id, i.product_id, i.product_name, i.subtotal, o.account_id, o.store_id,
       'Demo CU-22: el producto llegó roto y la tienda no respondió.', 'RESOLVED', 'REFUND_GRANTED',
       'Soporte confirmó el daño con las fotos del comprador.', i.subtotal,
       @ahora - INTERVAL 12 DAY, @ahora - INTERVAL 10 DAY
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.transaction_id = 'demo-datos-05'
  AND NOT EXISTS (SELECT 1 FROM claims c WHERE c.description LIKE 'Demo CU-22:%')
LIMIT 1;

-- Antecedente 2: publicación de la tienda 1 que moderación retiró.
INSERT INTO products (name, price, stock, category, active, store_id)
SELECT 'Publicación retirada demo CU-22', 30000, 0, 'Hogar', FALSE, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Publicación retirada demo CU-22');
SET @retirado = (SELECT id FROM products WHERE name = 'Publicación retirada demo CU-22');

INSERT INTO moderation_cases (content_type, content_id, status, report_count, open_key, version, opened_at, resolved_at)
SELECT 'PUBLICACION', CAST(@retirado AS CHAR), 'RESUELTO', 1, NULL, 0, @ahora - INTERVAL 30 DAY, @ahora - INTERVAL 28 DAY
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM moderation_actions WHERE agent_id = 'demo-cu22');
SET @caso = (SELECT MAX(id) FROM moderation_cases WHERE content_type = 'PUBLICACION'
             AND content_id = CAST(@retirado AS CHAR));

INSERT INTO moderation_actions (case_id, agent_id, decision, justification, previous_status, new_status,
                                measure_result, created_at)
SELECT @caso, 'demo-cu22', 'RETIRAR', 'Demo CU-22: la publicación ofrecía un producto falsificado.',
       'EN_REVISION', 'RESUELTO', 'APLICADA', @ahora - INTERVAL 28 DAY
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM moderation_actions WHERE agent_id = 'demo-cu22');
SQL
  if [ "$RESET_RESTRICTIONS" = "1" ]; then
    cat <<'SQL'

-- Reinicio de CU-22 (hijas primero). La auditoría y las notificaciones anteriores se conservan.
DELETE FROM account_restriction_changes;
DELETE FROM account_information_requests;
DELETE FROM account_restrictions;
UPDATE stores SET status = 'ACTIVE', status_reason = NULL WHERE status = 'RESTRICTED';
SQL
  fi
}

sql | mysql_exec

echo "Listo. Contraseña de las cuentas demo: MarketplaceDemo123!"
echo "  administración:   admin.demo@example.com  (rol ADMIN → menú «Restricciones de cuentas»)"
echo "  cuenta evaluada:  ${OWNER}  (dueña de la tienda 1; con rol VENDEDOR activo → «Mi cuenta»)"
echo "  antecedentes:     1 reclamación con reembolso + 1 publicación retirada por moderación"
[ "$RESET_RESTRICTIONS" = "1" ] && echo "Restricciones y solicitudes de información de CU-22 borradas."
exit 0
