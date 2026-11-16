# CU-22: demostración automática de restricciones de cuentas

Desde `frontend`, ejecutar `npm.cmd run demo:cu22`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4450 y abre Chrome visible. No
requiere Docker, MySQL ni el script de datos: la API está simulada dentro de la prueba, así que el recorrido sale
igual en cualquier computador. Dura unos tres minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu22-demo/restricciones-demo.spec.ts` y se hace en un solo navegador:

1. `admin.demo@example.com` inicia sesión, abre «Restricciones de cuentas» y busca `demo@marketplace.local`: 2
   antecedentes en 180 días (una reclamación con reembolso y una publicación retirada) y la marca «Reincidente».
2. Pide una aclaración y aplica una restricción de 30 días solo al rol Vendedor.
3. `demo@marketplace.local` inicia sesión: el rol Vendedor se rechaza con el aviso de restricción vigente y el rol
   Comprador sí funciona.
4. En «Mi cuenta» ve la restricción y su motivo, sin la identidad del administrador, y responde la aclaración.
5. La administración vuelve, lee la respuesta en la evaluación y retira la restricción antes de su fin.
6. `demo@marketplace.local` vuelve a elegir el rol Vendedor sin problema.

Cada paso comprueba lo que aparece en pantalla; si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba: restringir, responder y retirar cambian lo que se ve
después. Sirve para mostrar la interfaz del caso de uso completo, pero no certifica las reglas del backend. Esas
las comprueban las pruebas de integración `AccountRestrictionIntegrationTests` (13 pruebas, en la CI) contra MySQL
real; ver [CU-22: restricciones de cuentas](../casos-de-uso/cu-22-restricciones.md). Para probarlo a mano contra el
sistema real, esa misma guía explica el script `scripts/cu22-demo-seed.sh` y los pasos.

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu22-demo.config.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu22-demo
```
