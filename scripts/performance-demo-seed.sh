#!/usr/bin/env bash
# ============================================================================
# SOLO DESARROLLO / DEMOSTRACIÓN / PERFORMANCE. NUNCA PRODUCCIÓN.
# Genera datos sintéticos; no sustituye checkout, auditoría ni pagos reales.
# Usar una BD desechable, migrada por Flyway, sin tráfico durante el seed/reset.
# ============================================================================
set +x
set -euo pipefail
: "${DEMO_PASSWORD:?Define DEMO_PASSWORD}"
: "${DB_PASSWORD:?Define DB_PASSWORD}"
export MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-mkt}"
export DB_USER="${DB_USER:-marketplace_app}"
export DB_NAME="${DB_NAME:-marketplace_performance_demo}"
export DEMO_USERS="${DEMO_USERS:-100}"
export DEMO_PRODUCTS="${DEMO_PRODUCTS:-1000}"
export DEMO_ORDERS="${DEMO_ORDERS:-10000}"
export RESET_PERFORMANCE_DATA="${RESET_PERFORMANCE_DATA:-0}"
export DEMO_PASSWORD DB_PASSWORD
command -v docker >/dev/null
command -v python3 >/dev/null
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/performance-demo-seed.py"
