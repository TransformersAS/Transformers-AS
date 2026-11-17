#!/usr/bin/env bash
# ASR-004: tumba una réplica del backend y luego MySQL en el Swarm y mide cuánto tarda cada uno en recuperarse.
# Mientras tanto consulta el catálogo cada segundo, como lo haría un usuario, y cuenta las solicitudes que fallan.
#
# Uso:  bash scripts/quality/asr004-recuperacion.sh
# Variables opcionales: STACK (transformers), APP_URL (http://localhost:18000), API_URL (http://localhost:18080),
#                       LIMITE_SEGUNDOS (300, el criterio del ASR).
# Solo para un ambiente de prueba: la aplicación se cae a propósito durante la medición.
set -uo pipefail

STACK="${STACK:-transformers}"
APP_URL="${APP_URL:-http://localhost:18000}"
API_URL="${API_URL:-http://localhost:18080}"
LIMITE="${LIMITE_SEGUNDOS:-300}"
SALIDA="artifacts/quality/asr004-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$SALIDA"
SONDEO="$SALIDA/sondeo.csv"

hora() { date '+%H:%M:%S'; }
ahora() { date +%s; }
tareas_corriendo() {
  docker service ps "${STACK}_$1" --filter desired-state=running --format '{{.CurrentState}}' | grep -c '^Running' || true
}
listo() { [[ "$(curl -s -m 3 "$API_URL/actuator/health/readiness")" == *'"UP"'* ]]; }
catalogo_ok() { [[ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$APP_URL/api/products")" == 200 ]]; }

# ---------- Estado inicial ----------
echo "[$(hora)] Estado inicial del stack '$STACK':"
docker stack services "$STACK" --format '  {{.Name}} {{.Replicas}}'
BACKENDS=$(docker service inspect "${STACK}_backend" --format '{{.Spec.Mode.Replicated.Replicas}}')
if [[ "$(tareas_corriendo backend)" -ne "$BACKENDS" || "$(tareas_corriendo mysql)" -ne 1 ]] || ! listo || ! catalogo_ok; then
  echo "El stack no está completo y sano antes de empezar; no se mide." >&2
  exit 3
fi

# ---------- Sondeo continuo: una consulta al catálogo por segundo ----------
echo "segundo_unix,http" > "$SONDEO"
(
  while :; do
    codigo=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$APP_URL/api/products")
    echo "$(ahora),$codigo" >> "$SONDEO"
    sleep 1
  done
) &
SONDEADOR=$!
trap 'kill $SONDEADOR 2>/dev/null' EXIT

fallidas_entre() {
  awk -F, -v a="$1" -v b="$2" 'NR > 1 && $1 >= a && $1 <= b { t++; if ($2 != 200) f++ } END { printf "%d %d", f + 0, t + 0 }' "$SONDEO"
}

# Espera hasta que el servicio vuelva a tener sus tareas, la API esté lista y el catálogo responda.
esperar_recuperacion() {
  local servicio="$1" esperadas="$2" inicio="$3"
  while :; do
    if [[ "$(tareas_corriendo "$servicio")" -ge "$esperadas" ]] && listo && catalogo_ok; then
      echo $(( $(ahora) - inicio ))
      return
    fi
    if (( $(ahora) - inicio > LIMITE + 120 )); then
      echo -1
      return
    fi
    sleep 1
  done
}

RESULTADOS=()
medir() {
  local nombre="$1" servicio="$2" esperadas="$3"
  local contenedor
  contenedor=$(docker ps -q --filter "label=com.docker.swarm.service.name=${STACK}_$servicio" | head -1)
  [[ -n "$contenedor" ]] || { echo "No se encontró un contenedor de ${STACK}_$servicio" >&2; exit 3; }
  sleep 10
  local inicio
  inicio=$(ahora)
  echo "[$(hora)] Falla: se mata el contenedor ${contenedor:0:12} de $servicio (docker kill)."
  docker kill "$contenedor" >/dev/null
  local segundos
  segundos=$(esperar_recuperacion "$servicio" "$esperadas" "$inicio")
  sleep 5
  read -r fallidas total <<<"$(fallidas_entre "$inicio" "$(ahora)")"
  local veredicto="CUMPLE"
  if (( segundos < 0 || segundos > LIMITE )); then veredicto="NO CUMPLE"; fi
  echo "[$(hora)] $nombre: recuperado en ${segundos}s ($veredicto, límite ${LIMITE}s). Solicitudes fallidas: $fallidas de $total."
  RESULTADOS+=("{\"falla\":\"$nombre\",\"servicio\":\"$servicio\",\"recuperacion_segundos\":$segundos,\"limite_segundos\":$LIMITE,\"solicitudes\":$total,\"fallidas\":$fallidas,\"cumple\":$([[ $veredicto == CUMPLE ]] && echo true || echo false)}")
}

medir "Caída de una réplica del backend" backend "$BACKENDS"
medir "Caída de MySQL" mysql 1

kill $SONDEADOR 2>/dev/null
read -r fallidas total <<<"$(fallidas_entre 0 9999999999)"
printf '{"fecha_utc":"%s","stack":"%s","resultados":[%s],"sondeo_total":%d,"sondeo_fallidas":%d}\n' \
  "$(date -u +%FT%TZ)" "$STACK" "$(IFS=,; echo "${RESULTADOS[*]}")" "$total" "$fallidas" > "$SALIDA/resultado.json"

echo
echo "[$(hora)] Estado final:"
docker stack services "$STACK" --format '  {{.Name}} {{.Replicas}}'
echo "Disponibilidad durante toda la prueba: $(( total - fallidas )) de $total solicitudes correctas."
echo "Evidencia: $SALIDA/resultado.json y $SONDEO"
