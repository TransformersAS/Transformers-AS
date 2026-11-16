# CU-01: demostración visual del catálogo

Desde `frontend`, ejecutar `npm.cmd run demo:cu01`.

El comando compila el frontend, inicia un servidor independiente en el puerto
4441 y abre Chrome visible. Ejecuta una prueba a la vez con un segundo entre
acciones. No requiere avanzar en el inspector ni levantar Docker.

Ejecuta un único recorrido en `e2e-cu01-demo/catalogo-demo.spec.ts`, sin cerrar
el navegador entre pasos: buscar un pantalón y seleccionarlo,
consultar detalle e imágenes, regresar, combinar filtros, ordenar y comparar.
Se comparan un pantalón y una chaqueta de la categoría Ropa. La comparación
final permanece cinco segundos para poder leerla. Las imágenes son ilustrativas;
las dos posiciones de la galería del pantalón reutilizan la misma foto local.

Las respuestas de API son controladas por las pruebas: esta demostración no
modifica MySQL ni certifica la integración con el backend. Las cinco pruebas
habituales de `e2e/catalogo.spec.ts` permanecen separadas e intactas; incluyen
además errores y otros escenarios. Esta presentación no las sustituye.

La velocidad se configura en `frontend/playwright.cu01-demo.config.ts`, mediante
`launchOptions.slowMo` (milisegundos). No representa el tiempo de respuesta del
sistema: es una pausa para observar las acciones.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu01-demo
```
