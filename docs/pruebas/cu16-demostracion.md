# CU-16: demostración automática de promociones y cupones

Desde `frontend`, ejecutar `npm.cmd run demo:cu16`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4475 y abre Chrome visible. No
requiere Docker, MySQL ni datos cargados: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos dos minutos y medio; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu12-17-demo/promociones-demo.spec.ts`:

1. **Vendedor** (`ventas@sonidoandino.co`) abre «Promociones y cupones» y crea «Navidad sonora» para el parlante y los
   audífonos. El sistema rechaza un porcentaje de 150 y el código reservado DESC10. Con 20 % y el código `navidad20`
   se guarda como NAVIDAD20, inactivo, y el vendedor lo activa.
2. Crea «Bono fin de año» (BONO15K): 15.000 fijos para toda la tienda con compra mínima de 100.000. El sistema avisa
   que se cruza en fechas y productos con NAVIDAD20.
3. **Compradora** (`lucia.compras@example.com`) agrega audífonos (120.000) y un cable (12.000) y escribe `navidad20` en
   el checkout. El 20 % se aplica solo a los audífonos: descuento de 24.000 y total de 118.000 con el envío. Un código
   que no existe no descuenta y deja seguir sin él.
4. **Vendedor** desactiva NAVIDAD20; cuando la compradora lo vuelve a escribir, ya no aplica.

Cada paso comprueba lo que aparece en pantalla y, al final, los descuentos que calculó el checkout. Si algo no
coincide, la prueba se detiene en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba y responde como el backend: código en mayúsculas y
reservado, porcentaje máximo, conflictos entre cupones activos y descuento solo sobre los productos incluidos. Sirve
para mostrar la interfaz del caso de uso completo, pero no certifica las reglas del backend. Esas las comprueban
`SellerPromotionTests` (código único por tienda, fechas, productos de la tienda, tienda suspendida) y
`CheckoutCouponTests` (el aspecto que aplica el cupón en el checkout y el descuento guardado en el pedido pagado).

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu12-17-demo.base.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.
`DEMO_RAPIDA=1` corre el mismo recorrido sin ventana ni pausas, con la compilación que ya exista.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu16-demo
```
