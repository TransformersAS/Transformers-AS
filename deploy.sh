#!/usr/bin/env bash
set +x
set -euo pipefail

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

local_mode=false
case "${1:-}" in
  --local) local_mode=true ;;
  '') ;;
  *) fail 'Uso: ./deploy.sh [--local]. Consultar docs/swarm.md.' ;;
esac
[[ $# -le 1 ]] || fail 'Demasiados argumentos.'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export STACK_NAME="${STACK_NAME:-transformers}"
export BACKEND_IMAGE_TAG="${BACKEND_IMAGE_TAG:-latest}"
export FRONTEND_IMAGE_TAG="${FRONTEND_IMAGE_TAG:-$BACKEND_IMAGE_TAG}"
export DB_NAME="${DB_NAME:-marketplace}"
export DB_USER="${DB_USER:-marketplace_app}"
export DB_PASSWORD_SECRET="${DB_PASSWORD_SECRET:-${STACK_NAME}_db_password_v1}"
export MYSQL_ROOT_PASSWORD_SECRET="${MYSQL_ROOT_PASSWORD_SECRET:-${STACK_NAME}_mysql_root_password_v1}"
if "$local_mode"; then
  export BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-18080}"
  export FRONTEND_HOST_PORT="${FRONTEND_HOST_PORT:-18000}"
else
  export BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-8080}"
  export FRONTEND_HOST_PORT="${FRONTEND_HOST_PORT:-80}"
fi
deploy_timeout="${DEPLOY_TIMEOUT_SECONDS:-600}"
[[ "$STACK_NAME" =~ ^[a-z][a-z0-9_-]*$ ]] || fail 'STACK_NAME no válido.'
[[ "$BACKEND_IMAGE_TAG" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || fail 'BACKEND_IMAGE_TAG no válido.'
[[ "$FRONTEND_IMAGE_TAG" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || fail 'FRONTEND_IMAGE_TAG no válido.'
[[ "$DB_NAME" =~ ^[a-zA-Z][a-zA-Z0-9_]*$ ]] || fail 'DB_NAME no válido.'
[[ "$DB_USER" =~ ^[a-zA-Z][a-zA-Z0-9_]*$ && "$DB_USER" != root ]] || fail 'DB_USER debe ser una cuenta de aplicación.'
[[ "$BACKEND_HOST_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || fail 'BACKEND_HOST_PORT no válido.'
(( BACKEND_HOST_PORT <= 65535 )) || fail 'BACKEND_HOST_PORT fuera de rango.'
[[ "$FRONTEND_HOST_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || fail 'FRONTEND_HOST_PORT no válido.'
(( FRONTEND_HOST_PORT <= 65535 )) || fail 'FRONTEND_HOST_PORT fuera de rango.'
[[ "$BACKEND_HOST_PORT" != "$FRONTEND_HOST_PORT" ]] || fail 'Los puertos publicados de frontend y backend deben ser distintos.'
[[ "$deploy_timeout" =~ ^[1-9][0-9]{0,3}$ ]] || fail 'DEPLOY_TIMEOUT_SECONDS debe estar entre 1 y 9999.'
for secret_name in "$DB_PASSWORD_SECRET" "$MYSQL_ROOT_PASSWORD_SECRET"; do
  [[ "$secret_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || fail 'Nombre de secret no válido.'
done
[[ "$DB_PASSWORD_SECRET" != "$MYSQL_ROOT_PASSWORD_SECRET" ]] || fail 'Usa secrets distintos para aplicación y root.'

command -v docker >/dev/null || fail 'Docker no está instalado.'
command -v curl >/dev/null || fail 'Se requiere curl para comprobar readiness.'
docker info >/dev/null || fail 'Docker no responde.'
swarm_state="$(docker info --format '{{.Swarm.LocalNodeState}}')"
case "$swarm_state" in
  inactive) "$local_mode" || fail 'Inicializa el cluster real explícitamente; --local solo sirve para pruebas de un nodo.' ;;
  active) [[ "$(docker info --format '{{.Swarm.ControlAvailable}}')" == true ]] || fail 'Ejecuta este script en un manager.' ;;
  *) fail "Estado Swarm no apto: $swarm_state" ;;
esac

# Check private-registry access and CPU compatibility before changing Swarm.
backend_image="ghcr.io/transformersas/transformers-as-backend:${BACKEND_IMAGE_TAG}"
frontend_image="ghcr.io/transformersas/transformers-as-frontend:${FRONTEND_IMAGE_TAG}"
server_platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
for image in "$backend_image" "$frontend_image"; do
  docker pull "$image" || fail "No se puede descargar $image. Verifica docker login ghcr.io, etiqueta y arquitectura."
  image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")"
  [[ "$image_platform" == "$server_platform" ]] || fail "Imagen $image_platform incompatible con este nodo $server_platform; no se asume emulación."
done
docker image inspect --format '{{json .Config.Healthcheck.Test}}' "$backend_image" |
  grep -q '/actuator/health/liveness' || fail 'La imagen debe incluir el healthcheck de liveness.'
docker image inspect --format '{{json .Config.Healthcheck.Test}}' "$frontend_image" |
  grep -q '/healthz' || fail 'La imagen frontend debe incluir el healthcheck de /healthz.'

if [[ "$swarm_state" == inactive ]]; then
  printf 'Inicializando Swarm local de un nodo con advertise-addr 127.0.0.1.\n'
  # Suppress join tokens printed by Docker. This loopback cluster is not for workers.
  docker swarm init --advertise-addr 127.0.0.1 >/dev/null
fi

# Preserve the original database placement when rerunning from another manager.
if [[ -z "${MYSQL_NODE_ID:-}" ]]; then
  if docker service inspect "${STACK_NAME}_mysql" >/dev/null 2>&1; then
    MYSQL_NODE_ID="$(docker service inspect --format '{{range .Spec.TaskTemplate.Placement.Constraints}}{{println .}}{{end}}' "${STACK_NAME}_mysql" |
      awk '$1 == "node.id" && $2 == "==" {print $3}')"
    [[ -n "$MYSQL_NODE_ID" ]] || fail 'No se pudo recuperar el nodo original de MySQL; configura MYSQL_NODE_ID.'
  else
    MYSQL_NODE_ID="$(docker info --format '{{.Swarm.NodeID}}')"
  fi
fi
export MYSQL_NODE_ID
[[ "$(docker node inspect --format '{{.Spec.Role}} {{.Status.State}} {{.Spec.Availability}}' "$MYSQL_NODE_ID")" == 'manager ready active' ]] ||
  fail 'MYSQL_NODE_ID debe identificar un manager ready/active.'
docker stack config -c "$script_dir/stack.yml" >/dev/null

ensure_secret() {
  local secret_name="$1" secret_file="$2" password
  if docker secret inspect "$secret_name" >/dev/null 2>&1; then
    printf 'Reutilizando secret %s (no se cambia su contenido).\n' "$secret_name"
    return
  fi
  if [[ -n "$secret_file" ]]; then
    [[ -r "$secret_file" && -s "$secret_file" ]] || fail "Archivo de secret ilegible o vacío para $secret_name."
    [[ "$(wc -l < "$secret_file")" -eq 0 ]] || fail 'Los archivos de contraseña deben tener una sola línea sin salto final.'
    docker secret create "$secret_name" - < "$secret_file" >/dev/null
  else
    [[ -t 0 ]] || fail "Falta archivo para $secret_name; configura DB_PASSWORD_SECRET_FILE / MYSQL_ROOT_PASSWORD_SECRET_FILE."
    read -r -s -p "Contraseña nueva para $secret_name: " password
    printf '\n'
    [[ -n "$password" ]] || fail 'No se permiten contraseñas vacías.'
    printf '%s' "$password" | docker secret create "$secret_name" - >/dev/null
    unset password
  fi
}
ensure_secret "$DB_PASSWORD_SECRET" "${DB_PASSWORD_SECRET_FILE:-}"
ensure_secret "$MYSQL_ROOT_PASSWORD_SECRET" "${MYSQL_ROOT_PASSWORD_SECRET_FILE:-}"

docker stack deploy --with-registry-auth --resolve-image always -c "$script_dir/stack.yml" "$STACK_NAME"

# Swarm has no depends_on readiness gate; failed backend starts are retried.
health_url="${SWARM_HEALTH_URL:-http://127.0.0.1:${BACKEND_HOST_PORT}/actuator/health/readiness}"
frontend_url="${SWARM_FRONTEND_URL:-http://127.0.0.1:${FRONTEND_HOST_PORT}/healthz}"
frontend_readiness_url="${SWARM_FRONTEND_READINESS_URL:-http://127.0.0.1:${FRONTEND_HOST_PORT}/api/actuator/health/readiness}"
deadline=$((SECONDS + deploy_timeout))
stable=0
while (( SECONDS < deadline )); do
  ready=true
  for service in frontend backend mysql; do
    expected=2
    [[ "$service" != mysql ]] || expected=1
    update_state="$(docker service inspect --format '{{if .UpdateStatus}}{{.UpdateStatus.State}}{{end}}' "${STACK_NAME}_${service}")"
    case "$update_state" in
      paused|rollback*) fail "${service}: despliegue ${update_state}; revisar docker service ps." ;;
      updating) ready=false ;;
    esac
    count="$(docker service ps --filter desired-state=running --format '{{.CurrentState}}' "${STACK_NAME}_${service}" |
      awk '/^Running / {n++} END {print n+0}')"
    [[ "$count" == "$expected" ]] || ready=false
  done
  if "$ready" \
    && curl --fail --silent --max-time 5 "$health_url" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
    && curl --fail --silent --max-time 5 "$frontend_url" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
    && curl --fail --silent --max-time 5 "$frontend_readiness_url" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    stable=$((stable + 1))
    if (( stable >= 3 )); then
      docker stack services "$STACK_NAME"
      printf '\nFrontend y API disponibles; réplicas convergidas y readiness accesible. Revisar también la salud de cada task en su nodo.\n'
      printf 'docker stack services %s\ndocker stack ps --no-trunc %s\ndocker service ls\n' "$STACK_NAME" "$STACK_NAME"
      exit 0
    fi
  else
    stable=0
  fi
  sleep 5
done
docker stack services "$STACK_NAME"
docker stack ps --no-trunc "$STACK_NAME"
fail "No convergió en ${deploy_timeout}s. Se conserva el stack para diagnóstico; no se borraron datos."
