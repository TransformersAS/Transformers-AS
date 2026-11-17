#!/usr/bin/env bash
# CU-15 contra la aplicación real: entrada, ajuste, mínimo e historial de inventario, y lo que quedó en MySQL.
# Uso (raíz del repo, Docker abierto, Git Bash):  bash scripts/demos/cu15-real.sh
source "$(dirname "$0")/real-comun.sh"
asegurar_app
export DEMO_SELLO="$SELLO"
echo "== CU-15 contra $URL =="
correr inventario-real.spec.ts

PRODUCTO=$(dato PRODUCTO_ID)
echo "-- Tabla products: stock y nivel mínimo del producto (antes tenía $(dato STOCK_INICIAL) unidades)"
sql "SELECT id, name, stock, min_stock FROM products WHERE id = $PRODUCTO;"
echo "-- Tabla stock_movements: los movimientos de esta demostración"
sql "SELECT id, type, quantity, stock_after, reason, created_at FROM stock_movements
     WHERE product_id = $PRODUCTO ORDER BY id DESC LIMIT 2;"
