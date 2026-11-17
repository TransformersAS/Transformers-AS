# CU-13: demostración automática de una reclamación de compra

Desde `frontend`, ejecutar `npm.cmd run demo:cu13`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4472 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos tres minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu12-17-demo/reclamaciones-demo.spec.ts` y pasa por los tres actores:

1. **Compradora** (`lucia.compras@example.com`): abre «Mis reclamaciones». Sin elegir compra y producto no puede
   enviar. Reclama el «Parlante Bluetooth Andes» del pedido #1042 con una foto como evidencia. Si intenta abrir otra por
   el mismo producto, el sistema no la deja duplicar.
2. **Vendedor** (`ventas@sonidoandino.co`): ve el problema y la evidencia y pide información. La compradora responde y
   la reclamación vuelve a quedar abierta.
3. **Vendedor**: ofrece un reembolso de 250.000, mayor a los 180.000 pagados, y el sistema lo rechaza. Propone uno
   parcial de 50.000.
4. **Compradora**: puede aceptar la propuesta, pero escala a soporte con su motivo.
5. **Soporte** (`soporte.demo@example.com`): ve el historial completo y concede el reembolso de 180.000 con una
   explicación. La reclamación sale de las escaladas y aparece entre las resueltas.
6. **Compradora**: ve la reclamación resuelta con el reembolso completado y ya no puede seguir tramitándola.

Cada paso comprueba lo que aparece en pantalla; al final se revisa que haya una sola reclamación y un único reembolso
de 180.000. Si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba y responde como el backend: reclamación duplicada, estados
permitidos para cada acción, tope del reembolso y paso a resuelta. Sirve para mostrar la interfaz del caso de uso
completo con los tres roles, pero no certifica las reglas del backend (dueño de la compra, tienda del vendedor,
reembolso idempotente contra pagos), que comprueban sus pruebas en la CI.

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu12-17-demo.base.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.
`DEMO_RAPIDA=1` corre el mismo recorrido sin ventana ni pausas, con la compilación que ya exista.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu13-demo
```
