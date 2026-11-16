# CU-18: demostración automática de la configuración de la tienda

Desde `frontend`, ejecutar `npm.cmd run demo:cu18`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4452 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos dos minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu18-demo/mi-tienda-demo.spec.ts`, con la cuenta `vendedor.demo@example.com`:

1. Abre «Mi tienda»: ve la configuración guardada y cómo ven la tienda los compradores.
2. Edita y baja el plazo de devolución a 15 días. El sistema no deja continuar y explica, junto al campo, que el
   mínimo obligatorio del marketplace es de 30 días (A5). No se envía nada.
3. Sube el plazo a 45 días, completa descripción, teléfono, horarios y política, agrega el envío exprés y elige una
   imagen de portada.
4. Revisa la vista previa: cada cambio aparece como antes → después y la tarjeta muestra la tienda como quedaría.
   Todavía no se ha guardado nada.
5. Confirma: se suben la portada y la configuración, y la tienda queda guardada con su nueva versión.

Cada paso comprueba lo que aparece en pantalla; si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba y rechaza, como el backend, un plazo menor al mínimo y una
versión desactualizada. Sirve para mostrar la interfaz del caso de uso completo, pero no certifica las reglas del
backend (nombre único, versión, formatos e imágenes), que comprueban sus pruebas en la CI.

`e2e/mi-tienda-errores.spec.ts` es una prueba de regresión aparte: comprueba que cada error de validación se muestra
junto a su campo. Antes, con Ionic 8, solo aparecía el aviso general «Revisa los campos marcados», porque Ionic
reemplazaba las clases `ion-invalid`/`ion-touched` y no dibujaba el texto de error de cada campo.

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu18-demo.config.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu18-demo
```
