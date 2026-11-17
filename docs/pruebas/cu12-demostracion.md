# CU-12: demostración automática del registro de vendedores

Desde `frontend`, ejecutar `npm.cmd run demo:cu12`.

El comando compila el frontend, inicia un servidor independiente en el puerto 4471 y abre Chrome visible. No
requiere Docker, MySQL ni correo: la API está simulada dentro de la prueba, así que el recorrido sale igual en
cualquier computador. Dura unos dos minutos; un aviso en la esquina inferior derecha narra cada etapa.

El recorrido está en `e2e-cu12-17-demo/registro-vendedor-demo.spec.ts`:

1. Un visitante abre «Conocer el espacio vendedor» y lee las condiciones para vender (versión 2026-09).
2. Escribe un correo que ya tiene cuenta: el sistema no crea otra y le indica que inicie sesión y habilite el rol.
3. Cambia a un correo nuevo y repite mal la contraseña: el botón de registro sigue desactivado.
4. Pide el nombre «Tienda Sonido Andino», que ya existe: el nombre de la tienda es único y no queda creada la cuenta.
5. Elige «Cerámica del Páramo»: la tienda queda reservada y se pide confirmar el correo con el código enviado. Un código
   equivocado no avanza; el correcto confirma el registro.
6. Entra con la cuenta nueva: tiene los roles COMPRADOR y VENDEDOR, elige VENDEDOR y abre «Mis productos».
7. Lucía, que ya es compradora, habilita el rol de vendedor con su misma cuenta («Ya tengo cuenta»). Como su correo ya
   estaba confirmado, queda vendedora de inmediato con su tienda «Tejidos Lucía».

Cada paso comprueba lo que aparece en pantalla; al final se revisa que haya dos tiendas nuevas, que Lucía tenga los dos
roles y que ambas cuentas hayan aceptado la versión 2026-09 de las condiciones. Si algo no coincide, la prueba se detiene
en rojo e indica dónde.

## Qué demuestra y qué no

La API simulada guarda su propio estado durante la prueba y responde como el backend: correo ya registrado, nombre de
tienda repetido, código de verificación inválido y concesión del rol al confirmar. Sirve para mostrar la interfaz del
caso de uso completo, pero no certifica las reglas del backend (política de contraseñas, nombre único, aceptación de
condiciones, envío y vencimiento del código), que comprueban sus pruebas en la CI.

## Velocidad e informe

La pausa entre acciones se configura en `frontend/playwright.cu12-17-demo.base.ts` con `launchOptions.slowMo`
(milisegundos). Es solo para poder seguir las acciones; no representa el tiempo de respuesta del sistema.
`DEMO_RAPIDA=1` corre el mismo recorrido sin ventana ni pausas, con la compilación que ya exista.

El informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu12-demo
```
