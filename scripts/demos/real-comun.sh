# Funciones comunes de las demos reales de CU-12 a CU-16 (scripts/demos/cuXX-real.sh). No se ejecuta solo.
# Las demos usan el Docker local (compose.yaml, puerto 4300) y muestran al final lo que quedó en MySQL.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

URL="${DEMO_URL:-http://localhost:4300}"
SELLO="$(date +%H%M%S)"

# Consulta MySQL dentro del contenedor, con el usuario y la contraseña que ya tiene el contenedor.
sql() {
  docker compose exec -T mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --table 2>/dev/null' <<<"$1"
}
# Igual que sql, pero devuelve solo el valor, sin tabla.
valor() {
  docker compose exec -T mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --default-character-set=utf8mb4 -N -B 2>/dev/null' <<<"$1"
}

asegurar_app() {
  if [[ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL/")" != 200 ]]; then
    echo "La aplicación no responde en $URL; levantando el Docker local (puede tardar unos minutos)..."
    docker compose up -d --wait || { echo "No se pudo levantar el Docker local." >&2; exit 1; }
  fi
}

# Corre la prueba de Playwright con Chrome visible y deja en DATOS las líneas CLAVE=valor que imprimió.
correr() {
  local salida
  salida=$(mktemp)
  (cd frontend && npx playwright test --config=playwright.cu12-17-real.config.ts "$1") | tee "$salida"
  local resultado=${PIPESTATUS[0]}
  DATOS=$(grep -oE '^[A-Z_]+=.*' "$salida" || true)
  rm -f "$salida"
  if [[ $resultado -ne 0 ]]; then
    echo "La demo falló; revisa el mensaje de arriba." >&2
    exit 1
  fi
  echo
  echo "== Lo que quedó guardado en MySQL =="
}

# Valor de una línea CLAVE=valor que imprimió la prueba.
dato() { grep -E "^$1=" <<<"$DATOS" | tail -1 | cut -d= -f2-; }
