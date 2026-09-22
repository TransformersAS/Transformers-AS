#!/usr/bin/env bash
# Requires Docker Compose, frontend npm ci, and Playwright Chrome; no local database or SMTP needed.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
export CU08_E2E_PORT="${CU08_E2E_PORT:-14300}"
compose=(docker compose --env-file /dev/null -p "cu08-real-$$" -f "$repo_dir/compose.e2e.yaml")
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then "${compose[@]}" logs --no-color --tail=100 || true; fi
  "${compose[@]}" down --volumes --remove-orphans || true
  exit "$result"
}
trap cleanup EXIT
"${compose[@]}" up --build --wait --wait-timeout 240
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/cu08-real-seed.sql
cd frontend
export CU08_REAL_BASE_URL="http://127.0.0.1:${CU08_E2E_PORT}"
npx playwright test --config=playwright.real.config.ts --project=chrome
