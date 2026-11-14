#!/usr/bin/env bash
# No automatic start or cleanup. Run individual commands only after approval.
set -euo pipefail
task_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project=cu08-h02-20261107
action="${1:-help}"
shift || true

# Ignore inherited app credentials, Compose overrides and dotenv files.
dc() {
  env -i PATH="$PATH" HOME="$HOME" \
    H02_ORIGIN_CONFIRMED="${H02_ORIGIN_CONFIRMED:-}" \
    H02_VERIFIED_ORIGIN="${H02_VERIFIED_ORIGIN:-}" \
    docker --context desktop-linux compose --env-file /dev/null \
    --project-directory "$task_dir" -p "$project" -f "$task_dir/compose.yaml" "$@"
}
local_context() {
  local endpoint
  endpoint="$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')"
  [[ "$endpoint" == unix://* ]] || { echo 'Refusing a nonlocal Docker context.' >&2; exit 1; }
  docker --context desktop-linux info --format '{{.ServerVersion}}' >/dev/null
}
confirmation() {
  [[ "${H02_ORIGIN_CONFIRMED:-}" == true ]] || { echo 'Origin observation must be confirmed first.' >&2; exit 1; }
  python3 - "${H02_VERIFIED_ORIGIN:-}" <<'PY'
import ipaddress,sys
try:
    a=ipaddress.ip_address(sys.argv[1])
    private_v4=any(a in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')) if a.version==4 else False
    assert a.is_loopback or private_v4
    assert str(a)!='172.30.240.10'
except (ValueError,AssertionError):
    sys.exit('Supply one exact verified loopback/RFC1918 address, not a proxy, range or list.')
PY
}
case "$action" in
  config)
    local_context
    dc config --quiet
    echo 'Observation configuration parses; no resources created.'
    ;;
  config-active)
    local_context; confirmation
    dc -f "$task_dir/activate.yaml" config --quiet
    echo 'Activation configuration parses; no resources created.'
    ;;
  build)
    local_context
    dc build backend
    dc build frontend
    ;;
  observe-up)
    local_context
    # Never reuse a preexisting volume/network/container, even with a matching name.
    for resource in "$project"_mysql_data "$project"_login_proxy "$project"_database "$project"_entry; do
      if docker --context desktop-linux volume inspect "$resource" >/dev/null 2>&1 ||
         docker --context desktop-linux network inspect "$resource" >/dev/null 2>&1; then
        echo "Refusing existing resource: $resource" >&2; exit 1
      fi
    done
    [[ -z "$(docker --context desktop-linux ps -aq --filter "label=com.docker.compose.project=$project")" ]] || {
      echo 'Refusing an existing project.' >&2; exit 1;
    }
    dc up -d --no-build --wait --wait-timeout 600
    ;;
  seed)
    local_context
    dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 --user="$MYSQL_USER" --database=cu08_h02_validation' < "$task_dir/seed.sql"
    ;;
  origins)
    local_context
    dc logs --no-log-prefix --tail=100 frontend | sed -n '/^h02-nginx /p'
    dc exec -T backend cat /tmp/h02-access/origin.log
    ;;
  activate)
    local_context; confirmation
    dc -f "$task_dir/activate.yaml" up -d --no-build --no-deps --wait --wait-timeout 600 backend
    ;;
  restart-backend)
    local_context; confirmation
    dc -f "$task_dir/activate.yaml" restart backend
    ;;
  clean)
    local_context
    for kind in network volume; do
      for resource in "$project"_mysql_data "$project"_login_proxy "$project"_database "$project"_entry; do
        if docker --context desktop-linux "$kind" inspect "$resource" >/dev/null 2>&1; then
          owner="$(docker --context desktop-linux "$kind" inspect "$resource" --format '{{index .Labels "com.docker.compose.project"}}')"
          [[ "$owner" == "$project" ]] || { echo "Refusing resource owned by another project: $resource" >&2; exit 1; }
        fi
      done
    done
    # No global prune, images, external volumes, or orphan deletion.
    dc down --volumes
    ;;
  *)
    echo 'Commands: config | config-active | build | observe-up | seed | origins | activate | restart-backend | clean'
    [[ "$action" == help ]] || exit 1
    ;;
esac
