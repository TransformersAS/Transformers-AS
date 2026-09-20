#!/usr/bin/env bash
# Hace de servicio logístico externo para la demostración de CU-24 y CU-25: envía al webhook del backend una
# actualización firmada con HMAC-SHA256, tal como lo haría el proveedor real. SOLO desarrollo.
#
# Uso:
#   LOGISTICS_WEBHOOK_SECRET='el-mismo-secreto-del-backend' ./scripts/cu24-25-demo-events.sh shipment <pedido> <TIPO> [eventId] [descripcion]
#   LOGISTICS_WEBHOOK_SECRET='...' ./scripts/cu24-25-demo-events.sh return <devolucion> <TIPO> [eventId] [descripcion]
#
# Tipos de pedido:      PICKED_UP, IN_TRANSIT, DELIVERY_EXCEPTION, DELIVERY_ATTEMPT_FAILED, NEXT_ATTEMPT_SCHEDULED,
#                       DELIVERED, RETURNED_TO_SELLER
# Tipos de devolución:  PICKUP_SCHEDULED, PICKED_UP, IN_TRANSIT, INCIDENT, PICKUP_FAILED, DELIVERED_TO_SELLER
#
# Sin eventId se genera uno nuevo; reenviar el mismo eventId demuestra la idempotencia (responde DUPLICATE).
# Variables: LOGISTICS_WEBHOOK_SECRET (obligatoria), BACKEND_URL (por defecto http://localhost:8080).
# Los identificadores siguen la convención del proveedor simulado: SIM-order-N / TRK-N y SIM-return-N / TRK-RN.
set -euo pipefail

: "${LOGISTICS_WEBHOOK_SECRET:?Define LOGISTICS_WEBHOOK_SECRET (el mismo valor con el que arrancó el backend)}"
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"

if [ "$#" -lt 3 ]; then
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi
KIND="$1"; ID="$2"; TYPE="$3"
EVENT_ID="${4:-demo-$(date +%s%N)}"
DESCRIPTION="${5:-}"

case "$KIND" in
  shipment) REF_FIELD="shipmentId"; REF="SIM-order-${ID}"; TRACKING="TRK-${ID}"; PATH_="/api/logistics/webhooks/shipments" ;;
  return)   REF_FIELD="returnId";   REF="SIM-return-${ID}"; TRACKING="TRK-R${ID}"; PATH_="/api/logistics/webhooks/returns" ;;
  *) echo "El primer argumento es shipment o return" >&2; exit 2 ;;
esac

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BODY="{\"eventId\":\"${EVENT_ID}\",\"${REF_FIELD}\":\"${REF}\",\"trackingCode\":\"${TRACKING}\",\"type\":\"${TYPE}\",\"occurredAt\":\"${NOW}\""
[ -n "$DESCRIPTION" ] && BODY="${BODY},\"description\":\"${DESCRIPTION}\""
if [ "$TYPE" = "DELIVERED" ] || [ "$TYPE" = "DELIVERED_TO_SELLER" ]; then
  BODY="${BODY},\"evidence\":{\"type\":\"SIGNATURE\",\"reference\":\"POD-${EVENT_ID}\"}"
fi
BODY="${BODY}}"

SIGNATURE="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$LOGISTICS_WEBHOOK_SECRET" -hex | sed 's/^.* //')"

echo "POST ${BACKEND_URL}${PATH_}  eventId=${EVENT_ID}"
curl --silent --show-error --write-out '\nHTTP %{http_code}\n' -X POST "${BACKEND_URL}${PATH_}" \
  -H 'Content-Type: application/json' -H "X-Logistics-Signature: ${SIGNATURE}" --data-binary "$BODY"
