# CU-03: pago sandbox real y verificación en MySQL

Esta prueba es independiente de `demo:cu03`. No simula API, Mercado Pago o MySQL.
Requiere intervención humana para iniciar sesión y pagar con datos de prueba del
proveedor. Solo verifica el camino aprobado: no significa liquidación real de
dinero ni reparto a vendedores. Deja compra, reserva, pago y pedido de prueba;
no los borra ni vacía carritos existentes.

## Preparación

1. Levantar el Marketplace habitual y MySQL en el proyecto `marketplace-pruebas`.
2. Mantener un túnel HTTPS vigente hacia el frontend, con su proxy `/api`.
   Si necesitas uno nuevo, en otra terminal:

   ```powershell
   docker run --rm cloudflare/cloudflared:latest tunnel --url http://host.docker.internal:4300
   ```

   Mantener esa terminal abierta. La URL puede cambiar al reiniciar el túnel.
3. En `.env`, configurar `MARKETPLACE_PUBLIC_ORIGIN` y `MERCADOPAGO_RETURN_URL`
   con el mismo origen HTTPS vigente; configurar también
   `MERCADOPAGO_NOTIFICATION_URL=<origen>/api/payments/mercadopago/webhook`.
   Conservar las credenciales de prueba, receptor y secreto existentes.
   Actualizar la URL del webhook en Mercado Pago si cambió el dominio.
4. Recrear backend con los archivos Compose usados habitualmente para que lea
   las variables. Para notificaciones y túnel:

   ```powershell
   docker compose --env-file .env --env-file .env.push -p marketplace-pruebas -f compose.yaml -f compose.notificaciones.yaml -f compose.tunel.yaml up -d backend frontend rabbitmq
   ```

5. Disponer de un comprador de pruebas del Marketplace con carrito vacío y una
   dirección registrada. La cuenta compradora sandbox de Mercado Pago es otra
   cuenta: se utiliza únicamente dentro del proveedor.

## Ejecución desde frontend

```powershell
npm.cmd run test:cu03:preflight
npm.cmd run test:cu03:sandbox
```

El primer comando comprueba HTTPS y tablas mediante SELECT, sin crear compras.
El segundo abre Chrome. Iniciar sesión manualmente como comprador cuando lo
indique. No pegar contraseñas ni tokens en la terminal. Hay cinco minutos para
iniciar sesión y ocho para terminar el pago en el proveedor y volver a la tienda.

Playwright exige carrito vacío; elige el primer producto activo con stock,
agrega una unidad, utiliza la dirección predeterminada y el envío inicial, calcula
el total y abre el sandbox. Para elegir un producto concreto antes de ejecutar:

```powershell
$env:CU03_PRODUCT_ID='2'
```

Completar el pago aprobado con cuenta y tarjeta de PRUEBA. No usar tarjetas
reales. Si hay CAPTCHA o verificación de acceso, resolverlos manualmente.
No cerrar Chrome ni el túnel. Al regresar, el backend debe confirmar la compra.
Si queda pendiente, rechazado o en revisión, la prueba no informa aprobación;
revisar el estado sin repetir el pago. No reintentar automáticamente este comando
después de un fallo: antes revisar carrito, checkout y pago existentes.

### Continuar un checkout ya creado

Si la prueba se interrumpe después de abrir Mercado Pago, conservar el ID que
imprime la terminal y ejecutar:

```powershell
$env:CU03_CHECKOUT_ID='ID-DEL-CHECKOUT'
npm.cmd run test:cu03:sandbox
```

Este modo inicia sesión, comprueba propiedad mediante el backend y recupera el
intento existente. No agrega productos ni crea otro checkout. Si está pendiente,
solo permite continuar con reserva vigente; si está aprobado, verifica sus
registros sin pagar otra vez. Rechazados, cancelados o en revisión no se pagan.
Al acabar, quitar la selección con
`Remove-Item Env:CU03_CHECKOUT_ID -ErrorAction SilentlyContinue`.

La captura del POST de checkout reenvía la solicitud al backend real con
`route.fetch`, sin reintentos, y entrega exactamente su cuerpo de respuesta.
Se lee antes de entregarlo al navegador: evita perderlo cuando la aplicación
navega inmediatamente a Mercado Pago. No sustituye la respuesta por datos ficticios.

## Comprobaciones automáticas

### Modo automático con credenciales locales

Completar exclusivamente en `.env` ignorado o variables de entorno:

```dotenv
CU03_BUYER_EMAIL=
CU03_BUYER_PASSWORD=
CU03_MP_TEST_USER=
CU03_MP_TEST_PASSWORD=
CU03_MP_TEST_CARD_LAST4=
CU03_MP_TEST_CARD_CVV=
```

Los dos primeros campos son la cuenta de pruebas del Marketplace. Los siguientes
corresponden al comprador de PRUEBA de Mercado Pago: usuario `TESTUSER` seguido
de números y su contraseña. Los dos últimos identifican una tarjeta de PRUEBA
guardada en esa cuenta; no usar una tarjeta real ni configurar el número completo.
No enviar estas credenciales al chat ni subir `.env` a Git.

Desde `frontend`: `npm.cmd run test:cu03:auto`. No hace falta reconstruir backend
por estas variables: las lee el ejecutor local. Se puede combinar con
`CU03_CHECKOUT_ID` para continuar un intento vigente sin crear otro.

Automatiza los formularios de acceso, selección de tarjeta guardada, CVV y un
solo clic en Pagar. No reintenta accesos, pagos ni solicitudes no idempotentes.
Si el proveedor ofrece «Elige un método de verificación», selecciona únicamente
el botón o enlace Contraseña. No elige E-mail ni obtiene códigos externos.
Si el proveedor exige CAPTCHA, código o identidad, rechaza el pago o cambia su
interfaz, la ejecución falla de forma explícita. No garantiza ejecución sin
intervención para cualquier variante de la interfaz externa. Usar modo asistido
para resolver verificaciones legítimas y continuar el intento existente.

Los reportes de esta prueba ocultan los valores configurados en mensajes y
contextos de error. Trazas, vídeo y capturas permanecen desactivados.

Validación del conductor automático: TypeScript correcto y seis pruebas
aisladas aprobadas en Chrome (`e2e/cu03-automatic.spec.ts`): configuración de
prueba, restricción de origen, acceso y confirmación única, detención por
identidad y bloqueo sin indicador sandbox. El acceso se comprueba tanto desde
usuario como directamente desde contraseña, ignorando inputs ocultos.
El recorrido desde usuario incluye el selector de método Contraseña con un
botón superpuesto, como el observado en el proveedor.
Esas páginas son controladas;
**no equivalen a un pago nuevo aprobado por Mercado Pago**. La validación externa
del modo automático queda pendiente hasta configurar las credenciales locales.

### Presentación del producto y del proceso

Una compra nueva abre primero el detalle real del producto, lo agrega al carrito,
muestra la dirección seleccionada y el total devuelto por el backend antes de
continuar al sandbox. El ejecutor agrega un rótulo temporal con el nombre y la
cantidad durante estas etapas, y al final con los artículos y total consultados
en MySQL. Ese rótulo pertenece a la prueba, no a la interfaz publicada, y no
inventa estados ni cambia los datos de compra.

Al usar `CU03_CHECKOUT_ID`, se presenta el resumen de los artículos del snapshot
de esa compra y se continúa el intento; no se vuelve a agregar al carrito ni se
reproduce una compra nueva. Esto evita duplicar pagos para mostrar el recorrido.

### Evidencia del pago real

- Estado aprobado consultado mediante el backend real.
- Checkout y pedido asociados al comprador autenticado.
- Importe esperado, importe observado y total del pedido iguales; moneda COP.
- Receptor igual al `MERCADOPAGO_COLLECTOR_ID` configurado.
- Observación externa `approved` y resolución `ORDER_LINKED`.
- Producto comprado y cantidad registrados en `order_items`.
- FIRST y SECOND con MATCH para la misma verificación y pago, valores coincidentes.
- Consulta posterior de estado conserva el pedido y existe un solo pedido por pago.

MySQL se consulta dentro del contenedor descubierto por etiquetas Compose; solo
se ejecutan SELECT con el usuario de la aplicación. No se expone su contraseña.
Opcional: `CU03_DOCKER_PROJECT` cambia el proyecto de Docker y
`CU03_REAL_BASE_URL` cambia el origen (debe coincidir con el retorno de `.env`).

La evidencia de una ejecución exitosa queda en
`artifacts/cu03-sandbox-real/<checkout-id>.json`, sin contraseñas, tokens,
direcciones ni identificadores de cuenta. Capturas, vídeo y trazas están apagados
para evitar registrar el inicio de sesión del proveedor.

## Validación inicial del 15/11/2026 (antes de corregir el túnel)

- Configuración Playwright: reconoce una prueba asistida.
- Helper JavaScript: comprobación sintáctica correcta.
- Consulta real MySQL: cinco tablas disponibles y consulta de evidencia sobre un
  checkout existente correcta. Eso solo valida la consulta, no un pago nuevo.
- Preflight HTTPS: falló con ENOTFOUND en el dominio configurado.
- Compra nueva de sandbox: **pendiente de ejecutar con túnel vigente**. No se
  declara aprobada esta integración a partir de las demostraciones controladas.
## Recorrido completo desde el producto

Desde `frontend`, `npm.cmd run test:cu03:auto -- --nuevo` ignora el ID de
reanudación únicamente para esta ejecución. Muestra el detalle del producto,
lo agrega al carrito, abre el carrito, muestra dirección y envío, calcula el
total real y continúa al sandbox. Requiere un carrito vacío y conserva las
restricciones de reservas y la confirmación única del pago. No borra compras,
reservas ni productos existentes del carrito.

La contraseña del proveedor se busca también en sus iframes HTTPS; se escribe
con eventos de teclado y se confirma en el mismo documento. Las pruebas
aisladas verifican ese caso, pero no certifican por sí solas el pago externo.

## Validación real posterior del 15/11/2026

- Reanudación del checkout `c1aff3e1-a6ac-46d0-bd14-9fb381791590`:
  aprobada, pedido 30, una prueba correcta en 56,9 segundos.
- Reanudación del checkout `99741521-81f7-4753-ae6e-a1f44af7de3b`:
  aprobada, pedido 31, una prueba correcta en aproximadamente un minuto.
- Recorrido completo con `npm.cmd run test:cu03:auto -- --nuevo`:
  **1 passed (1.3m)**. Checkout `cd260f62-0b03-49d7-ac3f-86a537308c16`,
  pedido **32**, una Camiseta basica demo, total **24.000 COP**.
  Se ejecutaron detalle, agregado al carrito, apertura del carrito, dirección,
  cálculo del total, autenticación del proveedor, CVV, confirmación única,
  retorno a la tienda y comprobación de MySQL.
- En ese recorrido se comprobaron importe, moneda, receptor, producto,
  cantidad, un único pedido y pareja FIRST/SECOND con resultado MATCH de
  la misma verificación. Consultar otra vez conservó el mismo pedido.
- Evidencia: `artifacts/cu03-sandbox-real/cd260f62-0b03-49d7-ac3f-86a537308c16.json`.
- TypeScript: `tsc --noEmit -p tsconfig.json`, salida 0.

Las ejecuciones previas fallidas se conservaron como tales. Las pruebas aisladas
incluyen contraseña en iframe y CVV en `secure-fields.mercadopago.com`.
La selección del método Contraseña puede repetirse hasta tres veces si no
avanza; eso no reenvía credenciales ni repite el pago. CAPTCHA y códigos de
verificación siguen requiriendo intervención humana. Una aprobación sandbox
no acredita movimiento o liquidación de dinero real.

