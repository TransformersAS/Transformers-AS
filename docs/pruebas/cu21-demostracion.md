# CU-21: demostración automática de la moderación de reportes

Desde `frontend`, ejecutar `npm.cmd run demo:cu21`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4454 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos dos minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu21-demo/moderacion-demo.spec.ts`, con la cuenta `soporte.demo@example.com`:

1. Abre «Moderación de reportes»: un caso de una publicación con 2 reportes de personas distintas.
2. En el detalle ve el contenido, cada reporte con su motivo, la evidencia y un caso anterior que se mantuvo.
3. Toma el caso; queda asignado a su nombre. Toda acción exige una justificación de al menos 10 caracteres.
4. Oculta la publicación temporalmente, por precaución, mientras investiga.
5. Pide información al primer reportante (72 horas). Con la solicitud abierta no se puede mantener ni retirar.
6. El reportante responde (eso es CU-20, aquí simulado). El agente ve la respuesta y retira la publicación.
7. El caso queda resuelto, con la decisión y su justificación, y aparece en «Casos resueltos».

Cada paso comprueba lo que aparece en pantalla; si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba y rechaza, como el backend, decidir con una solicitud de
información vigente o con una versión desactualizada del caso. Lo que hace el reportante pertenece a CU-20, que tiene
su propia demostración ([CU-20](cu20-demostracion.md)).

Sirve para mostrar la interfaz del caso de uso, pero no certifica las reglas del backend, que comprueban las pruebas
del módulo de moderación en la CI. Una publicación retirada cuenta después como antecedente de la tienda en CU-22.

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu21-demo.config.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu21-demo
```
