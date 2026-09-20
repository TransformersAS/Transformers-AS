# Contrato del servicio externo de notificaciones

Contrato que el marketplace espera del servicio de avisos (correo, push u otro). Lo consume el adaptador
`HttpExternalNotificationGateway` (`notifications.provider=http`); el adaptador `simulated` (por defecto) lo imita.

## Principio de diseño

La **notificación interna** (tabla `notifications`) es la fuente de verdad y se guarda **en la misma transacción**
que el cambio de negocio que la origina. El **aviso externo** es un efecto secundario que sale **después del commit**,
desde un ejecutor acotado. Un fallo del servicio externo nunca revierte el cambio ni oculta la notificación interna
(A7); solo deja `external_status = FAILED` con el motivo en `last_error`.

## Enviar un aviso

`POST {notifications.http.base-url}/notifications`

| Cabecera | Valor |
| --- | --- |
| `Content-Type` | `application/json` |
| `Idempotency-Key` | el `eventKey` de la notificación |
| `X-Correlation-Id` | id de correlación del cambio que originó el aviso (RNF-038) |

```json
{
  "eventKey": "order-42-IN_PREPARATION",
  "recipient": { "type": "BUYER", "id": null },
  "type": "ORDER_IN_PREPARATION",
  "title": "Tu pedido está en preparación",
  "message": "Estamos preparando tu pedido #42.",
  "reference": { "type": "ORDER", "id": "42" }
}
```

`recipient.type` es `BUYER` o `STORE`; `recipient.id` puede ser `null` mientras el comprador no esté identificado por
cuenta. El contenido no incluye datos personales (dirección, teléfono).

| Código | Significado | Comportamiento del marketplace |
| --- | --- | --- |
| `2xx` | Aviso aceptado (repetir la misma `Idempotency-Key` también responde `2xx`) | `external_status = SENT` |
| `4xx` | Rechazo definitivo | `FAILED`; no cuenta para el circuito |
| `5xx`, timeout, red | Fallo temporal | `FAILED`; el circuito lo cuenta |

## Política de resiliencia

Timeout de 10 s como máximo (`notifications.http.timeout`), Circuit Breaker `notifications` (mismos parámetros que
logística: ventana 10, mínimo 5, 50 %, 30 s abierto), HTTPS obligatorio (`notifications.http.require-https`) y **sin
reintentos automáticos**. `attempts` cuenta los envíos; un barrido futuro puede reintentar los `PENDING`/`FAILED`
sin duplicar avisos porque el proveedor deduplica por `Idempotency-Key`.

Si el ejecutor acotado (2–4 hilos, cola de 100) se satura, el aviso no se pierde: la notificación queda `PENDING`.

## Cómo publicar una notificación desde otro caso de uso

```java
// Debe ejecutarse dentro de la transacción del cambio de negocio (Propagation.MANDATORY).
publishNotification.execute(new PublishNotificationCommand(
        RecipientType.BUYER, null, "ORDER_IN_PREPARATION",
        "Tu pedido está en preparación", "Estamos preparando tu pedido #42.",
        "ORDER", "42", "order-42-IN_PREPARATION"));
```

`eventKey` debe identificar el evento de negocio de forma estable: publicar el mismo `eventKey` dos veces produce
una sola notificación y un solo aviso externo. Convención: `{entidad}-{id}-{evento}`.
