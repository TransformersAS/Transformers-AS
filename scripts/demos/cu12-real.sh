#!/usr/bin/env bash
# CU-12 contra la aplicación real: un visitante se registra como vendedor, confirma el correo con el código que le
# llega y entra con su tienda; al final se muestra lo que quedó en MySQL.
# Uso (raíz del repo, Docker abierto, Git Bash):  bash scripts/demos/cu12-real.sh
#
# El código llega por correo: el script usa Mailpit, el buzón local que atrapa los correos (compose.mailpit.yaml y la
# carpeta .mailpit, solo en este PC). Si Mailpit no está corriendo, recrea el backend con esa configuración.
source "$(dirname "$0")/real-comun.sh"

MAILPIT="${DEMO_MAILPIT:-http://localhost:8025}"
if [[ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$MAILPIT/api/v1/info")" != 200 ]]; then
  [[ -f compose.mailpit.yaml && -d .mailpit ]] || {
    echo "Falta Mailpit (compose.mailpit.yaml y .mailpit): sin buzón no llega el código de verificación." >&2; exit 1; }
  echo "Levantando Mailpit y conectando el backend al buzón local (tarda un par de minutos)..."
  docker compose -f compose.yaml -f compose.mailpit.yaml up -d --wait || { echo "No se pudo levantar Mailpit." >&2; exit 1; }
  # nginx guarda la IP vieja del backend: se reinicia el frontend para que la vuelva a resolver.
  docker compose restart frontend >/dev/null && sleep 5
fi
asegurar_app

export DEMO_SELLO="$SELLO"
echo "== CU-12 contra $URL · buzón $MAILPIT =="
correr registro-vendedor-real.spec.ts

CORREO=$(dato CORREO)
echo "-- Tabla user_accounts: la cuenta nueva con el correo confirmado"
sql "SELECT id, email, status, email_verified_at FROM user_accounts WHERE email = '$CORREO';"
echo "-- Tabla user_account_roles: los roles de la cuenta"
sql "SELECT r.account_id, r.role FROM user_account_roles r JOIN user_accounts a ON a.id = r.account_id
     WHERE a.email = '$CORREO';"
echo "-- Tabla stores: la tienda creada a nombre de la cuenta"
sql "SELECT s.id, s.name, s.owner_account_id FROM stores s JOIN user_accounts a ON a.id = s.owner_account_id
     WHERE a.email = '$CORREO';"
echo "-- Tabla seller_terms_acceptances: la versión de las condiciones que aceptó"
sql "SELECT t.account_id, t.terms_version, t.accepted_at FROM seller_terms_acceptances t
     JOIN user_accounts a ON a.id = t.account_id WHERE a.email = '$CORREO';"
