# CU-20: demostración automática de reportes de contenido

Desde `frontend`, ejecutar `npm.cmd run demo:cu20`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4453 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos dos minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu20-demo/reportes-demo.spec.ts`, con la cuenta `comprador.demo@example.com`:

1. En el catálogo, un reloj «original con garantía oficial» a un precio imposible. El comprador pulsa «Reportar».
2. Elige «El producto llegó defectuoso o distinto»: el formulario explica que es un problema de compra, lo orienta a
   reclamaciones y devoluciones y no deja enviar.
3. Elige «Información engañosa o falsa» e intenta enviar sin describir: el error aparece junto a la descripción.
4. Describe el problema, adjunta una captura como evidencia y radica: recibe el reporte #214, Pendiente.
5. Lo reporta otra vez con el mismo motivo: no se crea otro; el sistema lo lleva a su reporte #214 con la evidencia.
6. Soporte toma el caso, oculta la publicación por precaución (desaparece del catálogo) y pide información. En «Mis
   reportes» aparece «Requiere tu respuesta» y el comprador responde dentro de las 72 horas.
7. Soporte retira la publicación. El comprador ve «El contenido fue retirado», pero no quién decidió ni por qué.

Cada paso comprueba lo que aparece en pantalla; si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba. Lo que hace soporte en los pasos 6 y 7 pertenece a CU-21
(moderación), que tiene su propia demostración; aquí solo se simula su efecto para ver lo que recibe el reportante.

Sirve para mostrar la interfaz del caso de uso, pero no certifica las reglas del backend (contenido propio,
duplicados, plazos, imágenes), que comprueban las pruebas del módulo `reports` en la CI. El contrato está en
[CU-20: reportes de contenido](../casos-de-uso/cu-20-reportes-contenido.md).

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu20-demo.config.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu20-demo
```
