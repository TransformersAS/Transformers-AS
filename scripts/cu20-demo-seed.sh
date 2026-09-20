#!/usr/bin/env bash
# Datos de demostración de CU-20 (reportes de contenido) para un MySQL local en Docker.
# SOLO desarrollo: crea las cuentas que hacen falta para probar cada alternativa del caso de uso.
#
#   soporte.demo@example.com   SOPORTE   agente que revisa el caso y ve las evidencias (CU-21)
#   comprador.demo@example.com COMPRADOR compradora distinta de la dueña de la tienda 1
#   vendedor.demo@example.com  VENDEDOR  dueña de la tienda 1 (no puede reportar sus propias publicaciones)
#   vecino.demo@example.com    VENDEDOR  dueña de la "Tienda vecina demo" (tienda 2), para que un vendedor
#                                        reporte la publicación de otra tienda
#
# Es repetible: volver a ejecutarlo restablece las contraseñas y no duplica cuentas, tiendas ni productos. Con
# RESET_REPORTS=1 también borra todos los reportes, casos y evidencias (útil para repetir la alternativa A3).
#
# Uso:
#   DEMO_PASSWORD='una-clave-local' DB_PASSWORD='la-clave-de-marketplace_app' ./scripts/cu20-demo-seed.sh
#
# Variables: DEMO_PASSWORD y DB_PASSWORD (obligatorias), MYSQL_CONTAINER (mysql-mkt), DB_USER (marketplace_app),
# DB_NAME (marketplace), RESET_REPORTS (0). Requiere Docker y que el backend haya arrancado antes al menos una vez.
set -euo pipefail

: "${DEMO_PASSWORD:?Define DEMO_PASSWORD (contraseña de las cuentas de prueba)}"
: "${DB_PASSWORD:?Define DB_PASSWORD (contraseña del usuario de la aplicación en MySQL)}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
DB_USER="${DB_USER:-marketplace_app}"
DB_NAME="${DB_NAME:-marketplace}"
RESET_REPORTS="${RESET_REPORTS:-0}"

# Hash bcrypt (htpasswd -B genera $2y$, que Spring Security acepta). La clave nunca se escribe en un archivo.
HASH="$(docker run --rm httpd:2 htpasswd -nbBC 10 demo "$DEMO_PASSWORD" | cut -d: -f2 | tr -d '\r\n')"

sql() {
  cat <<SQL
SET @hash = '${HASH}';

INSERT INTO user_accounts(email, password_hash, status) VALUES
  ('soporte.demo@example.com', @hash, 'ACTIVA'), ('comprador.demo@example.com', @hash, 'ACTIVA'),
  ('vendedor.demo@example.com', @hash, 'ACTIVA'), ('vecino.demo@example.com', @hash, 'ACTIVA')
  ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVA';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'SOPORTE' FROM user_accounts WHERE email = 'soporte.demo@example.com';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'COMPRADOR' FROM user_accounts WHERE email = 'comprador.demo@example.com';
INSERT IGNORE INTO user_account_roles(account_id, role)
  SELECT id, 'VENDEDOR' FROM user_accounts WHERE email IN ('vendedor.demo@example.com', 'vecino.demo@example.com');

-- Tienda 1 (la crea V13): la dueña es el vendedor demo si aún no tiene dueña. Tienda 2: del vecino.
UPDATE stores SET owner_account_id = (SELECT id FROM user_accounts WHERE email = 'vendedor.demo@example.com')
  WHERE id = 1 AND owner_account_id IS NULL
    AND (SELECT id FROM user_accounts WHERE email = 'vendedor.demo@example.com') NOT IN
        (SELECT owner_account_id FROM (SELECT owner_account_id FROM stores WHERE owner_account_id IS NOT NULL) AS o);
INSERT INTO stores(id, name) SELECT 2, 'Tienda vecina demo' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM stores WHERE id = 2);
UPDATE stores SET owner_account_id = (SELECT id FROM user_accounts WHERE email = 'vecino.demo@example.com')
  WHERE id = 2 AND owner_account_id IS NULL
    AND (SELECT id FROM user_accounts WHERE email = 'vecino.demo@example.com') NOT IN
        (SELECT owner_account_id FROM (SELECT owner_account_id FROM stores WHERE owner_account_id IS NOT NULL) AS o);

-- Una publicación por tienda para reportar.
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Camiseta demo', 45000, 20, 'Moda', TRUE, 1 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Camiseta demo');
INSERT INTO products(name, price, stock, category, active, store_id)
  SELECT 'Lámpara del vecino demo', 60000, 10, 'Hogar', TRUE, 2 FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'Lámpara del vecino demo');
SQL
  if [ "$RESET_REPORTS" = "1" ]; then
    cat <<SQL

-- Reinicio de reportes (hijas primero); las publicaciones vuelven a ser visibles.
DELETE FROM report_evidence_files;
DELETE FROM moderation_notifications;
DELETE FROM moderation_referrals;
DELETE FROM content_moderation_state;
DELETE FROM information_requests;
DELETE FROM moderation_actions;
DELETE FROM report_evidences;
DELETE FROM reports;
DELETE FROM moderation_cases;
SQL
  fi
}

sql | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" mysql --default-character-set=utf8mb4 -u"$DB_USER" "$DB_NAME"

echo "Listo. Cuentas de prueba (misma contraseña DEMO_PASSWORD):"
echo "  soporte:    soporte.demo@example.com     (rol SOPORTE)"
echo "  comprador:  comprador.demo@example.com   (rol COMPRADOR)"
echo "  vendedor:   vendedor.demo@example.com    (rol VENDEDOR, dueña de la tienda 1)"
echo "  vecino:     vecino.demo@example.com      (rol VENDEDOR, dueña de la tienda 2)"
[ "$RESET_REPORTS" = "1" ] && echo "Reportes, casos y evidencias borrados."
exit 0
