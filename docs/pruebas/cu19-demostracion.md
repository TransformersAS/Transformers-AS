# CU-19: demostración automática de una devolución

Desde `frontend`, ejecutar `npm.cmd run demo:cu19`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4451 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos tres minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu19-demo/devolucion-demo.spec.ts` y se hace en un solo navegador, alternando las cuentas
`comprador.demo@example.com` y `vendedor.demo@example.com`:

1. El comprador abre un pedido entregado, pulsa «Devolver», elige el motivo y describe la falla. Queda «Solicitada».
2. La tienda abre «Devoluciones recibidas», inicia la revisión y pide más información (24 horas para responder).
3. El comprador ve «Requiere tu respuesta» en «Mis devoluciones» y responde.
4. La tienda lee la respuesta y aprueba con una nota.
5. El comprador elige «Recogida en tu dirección» y ve el seguimiento del envío de retorno.
6. Logística entrega el paquete en la tienda: empiezan las 24 horas de inspección.
7. Sin problemas reportados, el sistema reembolsa. El comprador ve la devolución «Finalizada» y el historial completo.

Cada paso comprueba lo que aparece en pantalla; si algo no coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba: cada acción de una parte cambia lo que la otra ve después.
La llegada del paquete (en el sistema real la publica logística, CU-25) y el fin de la inspección (lo hace el barrido
programado cada minuto) se simulan entre etapas, sin esperar días ni horas reales.

Sirve para mostrar la interfaz del caso de uso completo, pero no certifica las reglas del backend. Esas las
comprueban las pruebas del paquete `returns` del backend (por ejemplo `ReturnBuyerApiTests`, `ReturnSellerApiTests`,
`ReturnInspectionSweepTests` y `ReturnMethodSelectionTests`), que corren en la CI. El flujo y sus reglas están en
[CU-19: devolución de compra](../casos-de-uso/cu-19-devolucion-compra.md).

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu19-demo.config.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu19-demo
```
