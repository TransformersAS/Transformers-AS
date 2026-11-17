#!/usr/bin/env bash
# CU-16 contra la aplicación real: el vendedor crea y activa un cupón y la compradora lo aplica en el checkout.
# Uso (raíz del repo, Docker abierto, Git Bash):  bash scripts/demos/cu16-real.sh
source "$(dirname "$0")/real-comun.sh"
asegurar_app
export DEMO_CODIGO="CLASE$SELLO"
echo "== CU-16 contra $URL · cupón $DEMO_CODIGO =="
correr cupones-real.spec.ts

echo "-- Tabla promotions: el cupón creado en la pantalla del vendedor"
sql "SELECT id, store_id, name, code, discount_type, discount_value, active, starts_at, ends_at
     FROM promotions WHERE code = '$DEMO_CODIGO';"
echo "-- Tienda 1: cupones guardados (los más recientes primero)"
sql "SELECT code, discount_type, discount_value, active, created_at FROM promotions WHERE store_id = 1
     ORDER BY id DESC LIMIT 5;"
echo "-- Checkout calculado por el backend con el cupón: $(dato CHECKOUT)"
