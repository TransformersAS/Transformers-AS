#!/usr/bin/env bash
# Despliega el tablero de monitoreo (Prometheus + cAdvisor + Grafana) junto a un stack del Marketplace que ya
# esté arriba. Uso: bash monitoreo.sh [--local]   ·   Para quitarlo: bash monitoreo.sh --quitar
set +x
set -euo pipefail

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

modo="${1:-}"
case "$modo" in
  --local|--quitar|'') ;;
  *) fail 'Uso: bash monitoreo.sh [--local | --quitar].' ;;
esac
[[ $# -le 1 ]] || fail 'Demasiados argumentos.'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export STACK_NAME="${STACK_NAME:-transformers}"
export MONITORING_STACK="${MONITORING_STACK:-${STACK_NAME}-monitoreo}"
export APP_NETWORK="${APP_NETWORK:-${STACK_NAME}_internal}"
export METRICS_TOKEN_SECRET="${METRICS_TOKEN_SECRET:-${STACK_NAME}_metrics_token_v1}"
export GRAFANA_ADMIN_PASSWORD_SECRET="${GRAFANA_ADMIN_PASSWORD_SECRET:-${MONITORING_STACK}_grafana_admin_v1}"
if [[ "$modo" == --local ]]; then
  export GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-13000}"
else
  export GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-3000}"
fi
[[ "$STACK_NAME" =~ ^[a-z][a-z0-9_-]*$ ]] || fail 'STACK_NAME no válido.'
[[ "$MONITORING_STACK" =~ ^[a-z][a-z0-9_-]*$ ]] || fail 'MONITORING_STACK no válido.'
[[ "$GRAFANA_HOST_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( GRAFANA_HOST_PORT <= 65535 )) || fail 'GRAFANA_HOST_PORT no válido.'

command -v docker >/dev/null || fail 'Docker no está instalado.'
[[ "$(docker info --format '{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}')" == 'active true' ]] ||
  fail 'Ejecuta este script en un manager del Swarm.'

if [[ "$modo" == --quitar ]]; then
  docker stack rm "$MONITORING_STACK"
  printf 'Monitoreo retirado. La aplicación (%s) no se tocó; la historia queda en el volumen %s_prometheus_data.\n' \
    "$STACK_NAME" "$MONITORING_STACK"
  exit 0
fi

docker network inspect "$APP_NETWORK" >/dev/null 2>&1 ||
  fail "No existe la red $APP_NETWORK: despliega primero la aplicación con deploy.sh (mismo STACK_NAME)."
docker secret inspect "$METRICS_TOKEN_SECRET" >/dev/null 2>&1 ||
  fail "No existe el secret $METRICS_TOKEN_SECRET: lo crea deploy.sh; vuelve a desplegar la aplicación."
docker service inspect "${STACK_NAME}_backend" \
  --format '{{range .Spec.TaskTemplate.ContainerSpec.Secrets}}{{println .SecretName}}{{end}}' 2>/dev/null |
  grep -qx "$METRICS_TOKEN_SECRET" ||
  fail "El backend de $STACK_NAME todavía no publica métricas: vuelve a desplegar la aplicación con deploy.sh."

if ! docker secret inspect "$GRAFANA_ADMIN_PASSWORD_SECRET" >/dev/null 2>&1; then
  generated="$(od -An -tx1 -N 24 /dev/urandom | tr -d ' \n')"
  [[ ${#generated} -eq 48 ]] || fail 'No se pudo generar la contraseña de administración de Grafana.'
  printf '%s' "$generated" | docker secret create "$GRAFANA_ADMIN_PASSWORD_SECRET" - >/dev/null
  unset generated
fi

# Swarm no deja modificar un config: su nombre lleva la huella del archivo, y al cambiar el archivo se crea otro.
huella() { sha256sum "$script_dir/monitoring/$1" | cut -c1-12; }
export PROMETHEUS_CONFIG_HASH="$(huella prometheus.yml)"
export GRAFANA_DATASOURCE_HASH="$(huella grafana-datasource.yml)"
export GRAFANA_DASHBOARDS_HASH="$(huella grafana-dashboards.yml)"
export GRAFANA_DASHBOARD_HASH="$(huella grafana-tablero.json)"

cd "$script_dir"
docker stack deploy --resolve-image changed -c stack.monitoreo.yml "$MONITORING_STACK"

url="http://127.0.0.1:${GRAFANA_HOST_PORT}"
deadline=$((SECONDS + ${MONITORING_TIMEOUT_SECONDS:-300}))
while (( SECONDS < deadline )); do
  if curl --fail --silent --max-time 5 "$url/api/health" | grep -q '"database"[[:space:]]*:[[:space:]]*"ok"'; then
    docker stack services "$MONITORING_STACK"
    printf '\nTablero disponible en %s (desde otro equipo: http://IP_DEL_MANAGER:%s).\n' "$url" "$GRAFANA_HOST_PORT"
    exit 0
  fi
  sleep 5
done
docker stack services "$MONITORING_STACK"
docker stack ps --no-trunc "$MONITORING_STACK"
fail 'Grafana no respondió a tiempo. Se conserva el stack para diagnóstico.'
