# Contrato del servicio logístico externo

Contrato que el marketplace espera del proveedor logístico. Lo consume el adaptador
`HttpLogisticsGateway` (`logistics.provider=http`); el adaptador `simulated` (por defecto) lo imita en proceso.
Hoy solo existe la creación de envíos. El seguimiento (CU-24) y los retornos (CU-25) se añadirán como operaciones
nuevas del mismo puerto `LogisticsGateway` sin alterar esta.

## Crear un envío

`POST {logistics.http.base-url}/shipments`

| Cabecera | Valor |
| --- | --- |
| `Content-Type` | `application/json` |
| `Idempotency-Key` | `order-{orderId}`, estable por pedido |
| `X-Correlation-Id` | id de correlación de la petición que originó la solicitud (RNF-038) |

Cuerpo:

```json
{
  "orderReference": "order-42",
  "shippingMethod": "STANDARD",
  "recipient": {
    "name": "…", "street": "…", "city": "…",
    "department": "…", "postalCode": "…", "phone": "…"
  },
  "items": [{ "name": "Lámpara", "quantity": 2 }]
}
```

`recipient` sale del **snapshot de entrega** guardado en el pedido al comprar, nunca de la dirección vigente del
comprador. `postalCode` puede ser `null`.

### Respuestas

| Código | Significado | Comportamiento del marketplace |
| --- | --- | --- |
| `201` | Envío creado: `{ "shipmentId", "trackingCode", "status": "CREATED" }` | Guarda la referencia; el pedido sigue en *Listo para despacho*. |
| `200` | La misma `Idempotency-Key` ya existía: devuelve **el mismo cuerpo** | Igual que `201`; no se crea otro envío. |
| `4xx` | Rechazo definitivo (datos inválidos) | No se reintenta automáticamente. El circuito **no** lo cuenta como fallo. Se informa al vendedor. |
| `5xx`, timeout, error de red | Fallo temporal | El circuito lo cuenta. El pedido sigue *Listo para despacho* y el vendedor puede reintentar. |
| Cuerpo ausente, no JSON, sin `shipmentId`/`trackingCode` o `status` distinto de `CREATED`; cualquier otro código | Respuesta inválida | Se trata como fallo temporal. |

Otros códigos `2xx`/`3xx` (por ejemplo `202`) se consideran respuesta inválida. Las redirecciones no se siguen.

## Política de resiliencia (RNF-042, RNF-043, RNF-045)

- **Timeout:** 10 s de conexión y 10 s de respuesta como máximo (`logistics.http.timeout`; un valor mayor
  a 10 s impide el arranque).
- **Circuit Breaker `logistics`** (Resilience4j, `application.properties`): ventana de 10 llamadas, mínimo 5,
  abre con 50 % de fallos, 30 s abierto, 3 llamadas de prueba en semiabierto. Con el circuito abierto se responde de
  inmediato sin llamar al proveedor.
- **Sin reintentos automáticos.** La creación de un envío no es segura de repetir a ciegas; el reintento lo
  inicia el vendedor (`POST /api/seller/orders/{id}/shipment`) y es seguro porque el proveedor deduplica por
  `Idempotency-Key` y la tabla `shipments` tiene `UNIQUE(order_id)`.
- **Nunca dentro de una transacción de BD.** La llamada ocurre después del *commit* del cambio de estado.
- **HTTPS obligatorio** (`logistics.http.require-https=true`, RNF-001). Solo se desactiva en pruebas con un servidor
  simulado.

## Proveedor simulado (RNF-037)

`logistics.provider=simulated` (valor por defecto). Es determinista e idempotente por `Idempotency-Key`:
`shipmentId = "SIM-order-{id}"`, `trackingCode = "TRK-{id}"`. `logistics.simulated.mode` permite probar
fallos en la demo: `OK` (por defecto), `UNAVAILABLE` (fallo temporal) o `REJECT` (rechazo definitivo).
