# CU-03: demostración continua de compra

Desde `frontend`, ejecutar `npm.cmd run demo:cu03`.

Chrome visible, un segundo entre acciones y un único recorrido. Compila el
frontend y usa el puerto 4443 sin requerir Docker ni credenciales.

Agrega pantalón y chaqueta al carrito, abre el checkout, busca la dirección de
oficina, selecciona envío Express, calcula $260.000 COP y continúa al pago.
La navegación al dominio sandbox es interceptada por Playwright con una página
que indica explícitamente que es un sustituto de prueba. No se introducen
tarjetas ni se realizan llamadas o cobros reales en Mercado Pago.

Al regresar, se comprueba que la interfaz consulte la API y muestre el pedido
aprobado de demostración. También se verifica el ID de dirección, envío, CSRF,
contenido del carrito y que consultar el estado no cree otra compra.

La API y su estado son controlados en memoria. No modifica MySQL ni demuestra
webhooks, aprobación real, doble chequeo del backend o persistencia. No reemplaza
las pruebas de integración ni la validación manual en el sandbox del proveedor.
Solo se muestra el camino aprobado; rechazo y revisión conservan sus pruebas
separadas en `e2e/mercadopago.spec.ts`.

Informe: `npx.cmd playwright show-report playwright-report-cu03-demo`.
Velocidad: `launchOptions.slowMo` en `frontend/playwright.cu03-demo.config.ts`.
