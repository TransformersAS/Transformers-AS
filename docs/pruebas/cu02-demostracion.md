# CU-02: demostración visual de recomendaciones

Desde `frontend`: `npm.cmd run demo:cu02`.

Compila el frontend e inicia un servidor en el puerto 4442. Abre Chrome con
un segundo entre acciones, en un único recorrido: recomendaciones de Ropa,
búsquedas de chaqueta, audífonos, cafetera y mochila, actualización de
recomendaciones, apertura de seis detalles y retorno, respuesta vacía y
continuación de la exploración del catálogo. Hay ocho productos de Ropa,
Tecnología, Hogar y Accesorios. Se mantiene una sola sesión de navegador.

Se comprueba que la interfaz envíe las interacciones SEARCH y VIEW y consulte
las recomendaciones actualizadas. Los productos tienen imágenes locales para
el detalle; las tarjetas de recomendaciones conservan su diseño actual sin fotos.

La API está controlada por Playwright. No modifica MySQL, no llama a IA ni prueba
su personalización real. Tampoco demuestra el filtro de visibilidad RF-143,
fallback del backend o NO_PRODUCTS del servicio: esos contratos requieren las
pruebas de integración del backend. La respuesta vacía solo prueba la interfaz.

El informe se abre con `npx.cmd playwright show-report playwright-report-cu02-demo`.
La velocidad se modifica en `frontend/playwright.cu02-demo.config.ts` mediante
`launchOptions.slowMo`, expresado en milisegundos.
