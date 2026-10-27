# CU-03: checkout con Mercado Pago en entorno de pruebas

## Qué cambia

El comprador ya no selecciona «simular aprobado/rechazado/pendiente». Después de calcular su compra pulsa **Continuar a Mercado Pago**, el backend crea una preferencia con el total calculado en el servidor y el navegador abre **Checkout Pro de Mercado Pago**. Los medios de pago disponibles se eligen en la pasarela, según la cuenta y el país; la aplicación no promete medios que el proveedor no ofrezca.

Esta integración está limitada a **pruebas**. El cliente del backend solo acepta el enlace `sandbox_init_point` del host `sandbox.mercadopago.com.co`. Al conciliar exige `live_mode=false`, o, si es `true`, consulta `/users/me` y verifica que el dueño del token coincida con el receptor configurado y tenga la etiqueta `test_user`. Esta excepción no permite cuentas receptoras reales ni omite las comprobaciones de importe, referencia o moneda. No es una habilitación de cobros de producción.

## Configuración que falta en tu cuenta

1. Crear la aplicación en Mercado Pago Developers Colombia siguiendo la guía de Checkout Pro vía Preferences API.
2. Preparar vendedor y comprador de prueba según el panel del proveedor. El comprador de prueba debe ser distinto del vendedor. Usar las tarjetas y datos de prueba de la documentación oficial.
3. Configurar las variables siguientes en el entorno del **backend**, o en `.env` si se ejecuta con el `compose.yaml` del proyecto. No incluir valores privados en Git, capturas o mensajes del chat.

```dotenv
MERCADOPAGO_ACCESS_TOKEN=
MERCADOPAGO_WEBHOOK_SECRET=
MERCADOPAGO_COLLECTOR_ID=
MERCADOPAGO_RETURN_URL=https://tu-host-publico/
MERCADOPAGO_NOTIFICATION_URL=https://tu-host-publico/api/payments/mercadopago/webhook
```

El Access Token debe corresponder al vendedor de pruebas y a la integración indicada por Mercado Pago. No se deduce el tipo de cuenta por el prefijo del token. `COLLECTOR_ID` es el identificador numérico de ese vendedor; no es el ID de la aplicación. El secreto del webhook es el que proporciona la configuración de notificaciones, no el Access Token.

Las URLs deben usar HTTPS, sin query string ni fragmento. La URL de retorno corresponde a la página del frontend. La de notificaciones debe llegar al backend por `/api/`. Para desarrollo se puede utilizar un túnel HTTPS hacia el frontend/proxy local; Mercado Pago no puede enviar una notificación a tu `localhost`. Debe preservarse el esquema HTTPS al pasar por el proxy. El Compose principal configura el reconocimiento de cabeceras reenviadas del backend; usar únicamente proxies de confianza.

4. En el panel de Mercado Pago, configurar Webhooks de prueba para eventos de pago y guardar su secreto.
5. Reconstruir/reiniciar frontend y backend con el procedimiento habitual del proyecto. Flyway aplica `V33__create_payment_checkouts.sql`.

Sin configuración, el inicio del checkout devuelve **503** con un mensaje explicativo. No se aprueba una compra localmente como alternativa. El endpoint antiguo `/api/payments/process` devuelve **410 Gone** en la aplicación normal. Su implementación simulada queda habilitada solo en la configuración de pruebas automatizadas para conservar los contratos anteriores.

## Flujo y controles implementados

1. `POST /api/payments/mercadopago/checkouts`, con sesión de comprador y CSRF: calcula el total, valida una sola tienda, reserva inventario por diez minutos y guarda un snapshot persistente de los productos, cantidades, precios y dirección. Las reservas se crean en el backend; el navegador no envía un total ni selecciona el resultado del pago.
2. El backend llama a `POST https://api.mercadopago.com/checkout/preferences`, con su Access Token privado. El snapshot se asocia mediante `external_reference` a un UUID local. El checkout externo vence con la reserva.
3. El frontend redirige al enlace sandbox devuelto por el backend. No recibe el Access Token ni recoge números de tarjeta.
4. El proveedor notifica `POST /api/payments/mercadopago/webhook?data.id=...`. Se valida HMAC-SHA256 con `x-signature` y `x-request-id`. Solo esta ruta de pagos permite notificaciones sin sesión/CSRF; la firma sustituye la autenticación del navegador para esta llamada servidor a servidor.
5. El backend consulta `GET /v1/payments/{id}` con su token. Comprueba referencia, vendedor receptor, moneda COP, importe exacto y condición de pruebas antes de actuar. Se observó que Checkout Pro puede devolver `live_mode=true` para un receptor que `/users/me` identifica como `test_user`; en ese caso se exige esa verificación adicional. Un fallo al consultar la cuenta impide aceptar el pago, y una cuenta normal o con otro ID se rechaza.
6. Un pago aprobado con reservas vigentes confirma el inventario y crea un solo pedido desde el snapshot. El bloqueo de la fila local evita crear pedidos duplicados ante notificaciones repetidas. Solo se eliminan del carrito las líneas compradas que mantienen su cantidad; no se borran cambios posteriores.
7. La página de retorno consulta el backend. Los parámetros de la URL, incluido `status=approved`, nunca autorizan una compra. Si llega un `payment_id`, se puede verificar mediante el endpoint protegido de refresco.

No se utilizan `localStorage` ni `sessionStorage`. La referencia del checkout queda en la URL y la información de compra en la base de datos. La cookie de sesión sigue siendo HttpOnly.

### Estados

| Estado local | Comportamiento |
| --- | --- |
| `PENDING` | Espera la aprobación. No crea pedido ni descuenta stock físico |
| `REJECTED` | El proveedor rechazó/canceló ese intento. No crea pedido; permite reintentar en el mismo checkout mientras la reserva siga vigente |
| `APPROVED` | Pago verificado y pedido confirmado |
| `REVIEW` | Requiere intervención: por ejemplo, aprobación después de vencer la reserva. No confirma inventario ni invita al comprador a pagar de nuevo |

Mientras existe un checkout vigente, una repetición con los mismos datos reutiliza su preferencia. Una petición con otros datos no crea un segundo checkout: exige terminar el anterior o esperar el vencimiento. Las reservas vencidas se excluyen de disponibilidad y el servicio existente las marca expiradas al procesarlas.

## Límites de esta entrega

- Solo sandbox y una tienda por compra, con una cuenta receptora de pruebas. No incluye reparto de pagos a cuentas de vendedores mediante OAuth/split.
- Los pagos aprobados después de vencer las reservas quedan en `REVIEW`. Su resolución es manual; no existe un reembolso automático de ese caso.
- Los reembolsos de pedidos `MP-...` no se envían a la pasarela simulada: el adaptador de protección registra un fallo explícito para gestionarlos desde el panel de Mercado Pago. No se presenta una devolución ficticia como completada.
- No se implementa en esta entrega el ciclo posterior de contracargos y reembolsos del proveedor ni una conciliación programada. Se procesan Webhooks y refrescos solicitados por el comprador. Una compra ya confirmada no cambia automáticamente su estado por un contracargo posterior.
- Una preferencia puede tener intentos de pago distintos. La aplicación no genera dos pedidos por el mismo checkout; cualquier cobro adicional deberá revisarse en el panel del proveedor. No usar esta preparación sandbox como habilitación de producción.
- El usuario creó la aplicación y ejecutó un pago con una tarjeta de prueba. La API reportó `approved`, con referencia, importe y receptor coincidentes y cuenta receptora marcada `test_user`. Esa comprobación reveló la diferencia del indicador `live_mode` descrita arriba. No acredita por sí sola la confirmación del pedido local ni todos los escenarios externos. Los tests automatizados siguen utilizando respuestas controladas y no realizan pagos.

## Prueba manual con el proveedor, pendiente de credenciales

1. Entrar como comprador del Marketplace; agregar productos de una sola tienda y calcular el total con dirección y envío.
2. Continuar a Mercado Pago y comprobar que el importe coincide con el total del resumen, incluido el envío y descuentos.
3. Pagar con una cuenta y tarjeta de prueba del proveedor. No utilizar datos ni tarjetas reales.
4. Comprobar la notificación en el panel y la respuesta del backend. Volver al Marketplace y consultar el estado: debe mostrar el pedido solo cuando el backend haya verificado la aprobación.
5. Revisar `payment_checkouts`, el pedido, las reservas y el stock; reenviar la misma notificación y confirmar que no aparece otro pedido.
6. Probar rechazo y pendiente con los datos oficiales correspondientes. Verificar que no se crea un pedido aprobado.
7. Intentar consultar la referencia con otra cuenta: debe responder 404. Cambiar `status` en la URL no debe aprobar nada.

## Pruebas automatizadas

Desde `backend/demo`, con Java 21, Maven y Docker:

```powershell
mvn.cmd -Dtest=MercadoPagoClientTests,MercadoPagoCheckoutIntegrationTests,CheckoutOrderCreationTests,BuyerIsolationIntegrationTests,CheckoutReservationIntegrationTests,CheckoutReservationValidationTests test
```

`MercadoPagoClientTests` verifica el contrato HTTP, credencial en cabecera, enlace sandbox, validación financiera y firma HMAC. `MercadoPagoCheckoutIntegrationTests` utiliza MySQL Testcontainers y respuestas controladas del adaptador externo; comprueba snapshots, aprobación, repetición, pendiente, rechazo, inventario vencido, permisos y CSRF. Las otras suites conservan la cobertura de checkout, reservas y aislamiento existentes.

Desde `frontend`, con Chrome disponible:

```powershell
npm.cmd run build
npx.cmd playwright test e2e/mercadopago.spec.ts e2e/persistent-session.spec.ts e2e/remaining-access-blockers.spec.ts --project=chrome --workers=2 --reporter=list
```

Las pruebas de navegador controlan la API y la navegación externa; no prueban los servidores de Mercado Pago. Los reportes locales se generan en `backend/demo/target/surefire-reports/` y `frontend/test-results/`.

### Resultados de la verificación del 26 de octubre de 2026

| Verificación | Resultado final |
| --- | --- |
| Compilación de Angular (`npm run build`) | Correcta |
| Navegador Chrome: nuevo checkout y regresión de sesión/acceso | 9 aprobadas, sin fallos |
| Backend: contrato HTTP, firma y protección de reembolsos | 6 aprobadas |
| Backend: integración del nuevo checkout con MySQL | 7 aprobadas |
| Regresión: creación de pedidos | 3 aprobadas |
| Regresión: aislamiento de compradores | 14 aprobadas |
| Regresión: integración de reservas | 26 aprobadas |
| Regresión: validación de reservas | 11 aprobadas |
| **Total de pruebas** | **76 aprobadas** |

Maven finalizó con `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0` y `BUILD SUCCESS`. Playwright finalizó con `9 passed`.

En ejecuciones previas se corrigieron una expectativa de prueba que confundía 200 con 201 al agregar al carrito y una comparación de vencimientos entre la hora de MySQL y la de Java. La ejecución final incluye las correcciones. Estos resultados corresponden a pruebas locales con respuestas controladas del proveedor: la transacción externa sigue pendiente de las credenciales de Mercado Pago.

## Referencias oficiales

Verificación adicional de la conciliación de cuentas de prueba: `MercadoPagoClientTests` (8 casos) y `MercadoPagoCheckoutIntegrationTests` (7 casos), **15 aprobadas, 0 fallos, 0 errores, 0 omitidas**. Incluye aceptación de `live_mode=true` exclusivamente con el receptor confirmado por `/users/me` como `test_user`, rechazo de cuenta normal/ID distinto y fallo cerrado si no se puede consultar esa identidad.

- [Checkout Pro](https://www.mercadopago.com.co/developers/es/docs/checkout-pro/overview).
- [Crear la preferencia](https://www.mercadopago.com.co/developers/es/docs/checkout-pro-preferences/create-payment-preference).
- [Notificaciones y firma](https://www.mercadopago.com.co/developers/es/docs/checkout-pro-preferences/payment-notifications).
- [Compras de prueba](https://www.mercadopago.com.co/developers/es/docs/checkout-pro-preferences/integration-test/test-purchases).
