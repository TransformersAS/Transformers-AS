# CU-19 · Devolución de compra

El comprador pide devolver un producto de un pedido entregado; la tienda lo revisa, pide información o decide; si aprueba,
sigue el retorno logístico (CU-25); al llegar al vendedor hay 24 h de inspección y, si no hay problema, se reembolsa.

Este documento describe lo que hay hecho en esta rama y sus limitaciones. El contrato del retorno con logística
(consultar métodos y crear el retorno) se documenta en `docs/contracts/logistics-api.md` cuando se implemente esa parte.

## Estados

`REQUESTED` → `IN_REVIEW` → (`INFO_REQUIRED` ↔ `IN_REVIEW`) → `APPROVED` → `IN_INSPECTION` → `REFUND_PENDING` → `FINISHED`.
`REJECTED` es terminal. Solo se rechaza desde `REQUESTED`, `IN_REVIEW` o `INFO_REQUIRED` con el plazo vencido (A5).

## API

Comprador (`/api/return-requests`, rol activo COMPRADOR, código `RETURN_BUYER_ROLE_REQUIRED` si no):

| Método y ruta | Qué hace |
|---|---|
| `GET /eligible-orders` | Pedidos entregados con cada línea y si se puede devolver, o el código y mensaje de por qué no (RF-048). |
| `POST /` | Solicita. JSON, o `multipart/form-data` con hasta 3 imágenes en `evidences`. 201 si es nueva; 200 con la existente si la línea ya tenía solicitud, en cualquier estado (A3). |
| `GET /{id}/return-methods` y `POST /{id}/return-method` | Métodos de retorno que logística ofrece y elección del método: crea el retorno en logística fuera de la transacción de base de datos, con la clave `return-{id}`. Si logística no responde, 503 `RETURN_LOGISTICS_UNAVAILABLE` y la devolución sigue Aprobada para reintentar. |
| `GET /`, `GET /{id}` | Sus devoluciones y el detalle con la línea de tiempo (RF-051). |
| `POST /{id}/information-response` | Responde la solicitud de información dentro de las 24 h. |
| `GET /{id}/evidences/{n}` | Una imagen, con `ETag` y `Cache-Control: private, no-cache` también en el 304. |

Tienda (`/api/seller/return-requests`, rol VENDEDOR y dueña de la tienda; la tienda sale de la sesión):
`GET /?status=`, `GET /{id}`, `POST /{id}/review`, `/information-requests`, `/approve`, `/reject` (justificación obligatoria),
`/report-problem` (dentro de la inspección) y `GET /{id}/evidences/{n}`. Una devolución de otra tienda responde 404.

Errores con código: `RETURN_ORDER_NOT_FOUND` (404), `RETURN_NOT_FOUND` (404), no elegible (422:
`RETURN_ORDER_NOT_DELIVERED`, `RETURN_WINDOW_EXPIRED`, `RETURN_DELIVERY_DATE_UNKNOWN`, `RETURN_LINE_NOT_IN_ORDER`),
datos incompletos (400 con `details.field`: `RETURN_REASON_REQUIRED`, `RETURN_DESCRIPTION_REQUIRED`, `IMAGE_*`,
`RETURN_TOO_MANY_IMAGES`), `RETURN_INVALID_STATE`, `RETURN_INFORMATION_PENDING`, `RETURN_INFORMATION_EXPIRED`,
`RETURN_INSPECTION_CLOSED`, `RETURN_PROBLEM_ALREADY_REPORTED` (409) y `REFUND_EXCEEDS_ORDER_TOTAL` (409).

## Reglas

- **Elegibilidad (A1)**: línea del pedido, pedido entregado y dentro del plazo de la tienda (`return_window_days`, mínimo 30),
  contado desde la fecha real de entrega. **Si no hay fecha de entrega la solicitud se rechaza como no elegible**
  (`RETURN_DELIVERY_DATE_UNKNOWN`); no se inventa. Las reglas "generales del marketplace" no están definidas: se enchufan
  declarando un bean `ReturnEligibilityRule` (por defecto no hay ninguna).
- **Fecha de entrega**: primera entrega aplicada en `shipment_tracking_events` (`DELIVERED`, `APPLIED`, por `occurred_at`) y,
  si no hay, la primera vez que el pedido pasó a `DELIVERED` en `order_status_history`.
- **Una devolución por línea** (`order_item_id` único) y siempre por la cantidad completa. Una línea rechazada no admite una
  solicitud nueva: se disputa por reclamación (CU-13).
- **Reembolso**: `unit_price × quantity` de la línea, sin envío, con la clave `return-{id}`. Ningún pedido se reembolsa por
  más de su total, sumando cancelación, reclamaciones y devoluciones.
- **Plazos** (`returns.*`): decisión de la tienda marcada "atrasada" a las 72 h y método de retorno "atrasado" a las 72 h
  (supuestos; solo marcas calculadas al consultar, sin decisión ni recordatorio automáticos); 24 h para responder
  información; 24 h de inspección; reintentos del reembolso cada 15 min hasta 5 veces.
- **Inspección**: empieza cuando logística publica `ReturnDeliveredToSeller` y cuenta 24 h desde que la devolución lo registra.
  Solo termina por vencimiento; el vendedor puede reportar un problema dentro de la ventana (`/report-problem`), lo que abre
  una reclamación a nombre del comprador con ese problema como descripción y detiene el reembolso automático.
- **Barrido**: cada minuto (`returns.sweep.*`) toma un lote pequeño de devoluciones vencidas con `SELECT ... FOR UPDATE SKIP
  LOCKED` (índice `(status, next_action_at)`), les pone una reserva y pide el reembolso fuera de la transacción. Dos réplicas no
  procesan lo mismo, y repetir el reembolso es idempotente por clave. Al llegar al máximo de intentos la devolución queda en
  `REFUND_PENDING` sin próxima acción, para revisión manual.
- **Reclamaciones (CU-13)**: `OpenReturnFromClaimUseCase` y su oyente ya están; una reclamación que exija devolver deja la
  devolución `APPROVED`, sin plazo y con el reembolso acordado, o **reabre la rechazada** (solo desde `REJECTED`) dejando la
  reapertura y el cambio de origen en la línea de tiempo.

## Limitaciones conocidas y pendientes

- **Inspección tras la entrega**: una devolución aprobada solo avanza a inspección si logística publica que el retorno llegó a
  la tienda; hoy eso pasa cuando CU-25 tiene un envío registrado con el mismo `return_id`, que se crea al elegir el método.
- **La línea se identifica por posición en la pantalla**: el detalle de un pedido (`GET /api/orders/{id}`) no expone el id de
  cada línea (`order_items.id`), y `GET /return-requests/eligible-orders` sí. El botón «Devolver» del pedido empareja ambas
  listas por posición y por producto, y por eso las dos se devuelven siempre `ORDER BY id` (`OrderEntity.items` con
  `@OrderBy("id ASC")` y `JdbcOrderForReturnReader`). Si la posición no coincide en producto, no se ofrece el botón antes que
  devolver una línea equivocada del mismo producto. Lo limpio sería exponer `id` en cada línea del detalle del pedido y usarlo
  directamente; queda como mejora en el módulo de pedidos. Lo cubre `ReturnBuyerApiTests` con un pedido que tiene el mismo
  producto en dos líneas.
- **Camino reclamación → devolución.** El camino reclamación -> devolución está implementado del lado de returns, pero CU-13
  aún no publica `ClaimResolvedRequiringReturn` ni evita el reembolso inmediato cuando la solución requiere devolución
  (RF-184 y A8 de CU-13). Mientras tanto esas soluciones se reembolsan sin devolución física; el tope de reembolsos por pedido
  evita pagos duplicados. `OpenReturnFromClaimUseCase` solo reacciona al evento (lo escucha `ReturnEventListeners`): ningún
  endpoint ni servicio lo invoca. El evento vive provisionalmente en `returns.application.event`; cuando CU-13 lo publique se
  moverá a su paquete y devoluciones lo importará de allí. Pregunta abierta: ¿la reclamación de un problema en inspección debe
  nacer ya escalada a soporte?
- **Recogida bloqueada**: no se construye el evento `ReturnPickupStopped`. `pickupBlocked` se deriva de
  `return_shipments.pickup_stopped` y solo se muestra en pantalla; CU-25 ya notifica al comprador y a la tienda cuando se detiene
  la recogida, y devoluciones no vuelve a notificar.
- **Reembolso por línea**: el tope es por pedido (`orders.total`, que incluye el envío). Limitarlo por línea exigiría guardar
  la línea en `refunds`.
- **Motivos** (`DEFECTIVE`, `NOT_AS_DESCRIBED`, `DAMAGED_IN_TRANSIT`, `WRONG_ITEM`, `CHANGED_MIND`, `OTHER`) y el máximo de 3
  imágenes de 5 MB son supuestos: el requisito no los define.
- **Fechas**: la aplicación usa la hora de su zona; `NOW()` de MySQL usa la del servidor. Con contenedores en UTC coinciden.
- **Interfaz** (`frontend/src/app/devoluciones/`): «Devolver» en el detalle del pedido entregado, «Mis devoluciones» y «Devoluciones
  recibidas». La lista de líneas elegibles se pide una sola vez, se comparte en memoria entre las líneas (60 s) y solo al abrir el
  detalle de un pedido entregado.
- **Aviso externo**: sigue sin reintento automático si falla el envío externo (A11); la notificación interna siempre queda.

## Contrato logístico propuesto

Propuesta para el retorno de una devolución aprobada (RF-109). **No se recrea `docs/contracts/logistics-api.md`** hasta que
quien mantiene logística confirme si su borrado en el commit `59ded8e2` fue voluntario. Es el mismo servicio, con la misma
política que crear envíos: timeout máximo de 10 s, circuit breaker `logistics` y **cero reintentos automáticos**; un 4xx es
un rechazo definitivo y no cuenta como fallo del circuito; 5xx, timeout, red, cuerpo inválido o circuito abierto son fallos
temporales. Todas las llamadas llevan `X-Correlation-Id`.

**Origen y destino.** El origen es la dirección de recogida del comprador, que sale del snapshot de entrega del pedido
(`orders.delivery_recipient_name`, `delivery_street`, `delivery_city`, `delivery_department`, `delivery_postal_code`,
`delivery_phone`), la misma con la que se le envió. El destino es la tienda: solo se envía su `storeId` y el servicio logístico
resuelve el lugar de entrega. Devoluciones no añade ninguna dirección a la tienda.

`GET /returns/methods?orderId={id}&storeId={id}` — métodos disponibles ahora (lectura sin efectos):

```json
{"methods": [{"code": "PICKUP", "label": "Recogida en mi dirección"},
             {"code": "DROP_OFF", "label": "Entrega en un punto de despacho"}]}
```

`POST /returns` con la cabecera `Idempotency-Key: return-{id}` — crea el retorno. Repetir la misma clave devuelve el mismo
retorno (200 en vez de 201) y nunca crea otro:

```json
{"returnReference": "return-42", "orderReference": "order-9", "storeId": 1, "method": "PICKUP",
 "pickup": {"name": "...", "street": "...", "city": "...", "department": "...", "postalCode": "...", "phone": "..."},
 "items": [{"name": "Lámpara", "quantity": 2}]}
```

Respuesta 200 o 201: `{"returnId": "RET-1", "trackingCode": "TRK-R1", "status": "CREATED"}`. `returnId` es la referencia del
proveedor (`provider_return_id` de CU-25) y `trackingCode` su guía; con ellas devoluciones llama a `RegisterReturnShipmentUseCase`.
Un método que dejó de estar disponible responde 4xx (409 o 422): devoluciones lo trata como rechazo definitivo y pide elegir
otro método.

En el proveedor simulado (por defecto) los métodos son `PICKUP` y `DROP_OFF`, la referencia es `SIM-return-{id}` y la guía
`TRK-R{id}`, y respeta los modos `OK`, `UNAVAILABLE` y `REJECT`.

## Datos de demostración

`scripts/cu19-demo-seed.sh` crea la compradora y cinco pedidos con distintas fechas de entrega, incluido un evento `DELIVERED`
con `occurred_at` controlable (`DELIVERED_DAYS_AGO` o `DELIVERED_AT`).
