#!/usr/bin/env bash
# Ejecuta exclusivamente CU-09 en MySQL y SMTP desechables.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
export CU08_E2E_PORT="${CU08_E2E_PORT:-14309}"
export CU09_MAIL_PORT="${CU09_MAIL_PORT:-18039}"
compose=(docker compose --env-file /dev/null -p "cu09-real-$$" -f "$repo_dir/compose.e2e.yaml" -f "$repo_dir/compose.cu09-e2e.yaml")
cleanup() { result=$?; trap - EXIT; "${compose[@]}" down --volumes --remove-orphans; exit "$result"; }
trap cleanup EXIT
"${compose[@]}" up --build --wait --wait-timeout 240
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < "$repo_dir/scripts/cu09-real-seed.sql"
cd "$repo_dir/frontend"
export CU08_REAL_BASE_URL="http://127.0.0.1:${CU08_E2E_PORT}"
export CU09_MAIL_URL="http://127.0.0.1:${CU09_MAIL_PORT}"
npx playwright test --config=playwright.real.config.ts --project=chrome e2e-real/cu09-profile.spec.ts
