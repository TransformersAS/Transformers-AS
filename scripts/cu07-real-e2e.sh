#!/usr/bin/env bash
# Disposable MySQL + SMTP simulator; never uses a persistent application database.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
export CU08_E2E_PORT="${CU08_E2E_PORT:-14307}"
export CU07_MAIL_PORT="${CU07_MAIL_PORT:-18025}"
compose=(docker compose --env-file /dev/null -p "cu07-real-$$" -f "$repo_dir/compose.e2e.yaml" -f "$repo_dir/compose.cu07-e2e.yaml")
cleanup() {
  result=$?
  trap - EXIT
  "${compose[@]}" down --volumes --remove-orphans
  exit "$result"
}
trap cleanup EXIT
"${compose[@]}" up --build --wait --wait-timeout 240
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/cu08-real-seed.sql
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/cu07-real-seed.sql
cd frontend
export CU08_REAL_BASE_URL="http://127.0.0.1:${CU08_E2E_PORT}"
export CU07_MAIL_URL="http://127.0.0.1:${CU07_MAIL_PORT}"
npx playwright test --config=playwright.real.config.ts --project=chrome
