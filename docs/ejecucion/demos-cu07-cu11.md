# Sustentación: CU-07 a CU-11

## Revisión y reutilización

Se inspeccionaron ambos repositorios locales. No se ejecutó Git ni se modificó
`Transformers-Integration-Tests`.

| Fuente existente | Decisión |
|---|---|
| `frontend/e2e-real/buyer-registration.spec.ts` y `scripts/cu07-real-e2e.sh` | Reutilizar registro visible, extracción privada de código Mailpit y verificación real. |
| `frontend/e2e-real/account-session.spec.ts`, runner CU08 | Conservar comprobación de invalidación del cookie antiguo contra backend. |
| `frontend/e2e-real/cu09-profile.spec.ts`, runner CU09 | Adaptar edición, confirmación de contraseña y persistencia; la demo no desactiva la cuenta. |
| `frontend/e2e-demo/demo-launcher.spec.ts`, `playwright.demo.config.ts` | Extraer CU08/CU11 a `account-order-flows.ts`; el guion conjunto conserva ambos flujos. |
| `frontend/e2e-cu10/conversations.spec.ts` | Adaptar contacto por producto/pedido y respuesta con dos contextos; autenticar por UI. No reutilizar inyección de fallos ni reportes de moderación. |
| Repositorio externo: runners CU07–CU11, helpers `cu08-auth`, `cu11-orders`, suites account-security, cu08-access, cu09-profile, conversations y checkout-order-cancellation | Consultar contratos y preparación SQL; sus runners incluyen fuentes fijadas/descargas y directorios de evidencia compartidos. Se dejan intactos. |
| `scripts/demos/run.py`, `seed.sql`, `compose.sustentacion.yaml`, config sustentación y comandos npm ya presentes | Completar escenarios y reporter ausentes; validar evidencia SQL además del resultado Playwright. |

Faltaban los cinco escenarios y el reporter referenciados por la configuración.
Se mantiene el build de producción servido por Nginx; no se usa `ng serve`.
No hay interceptaciones HTTP, cambios en contratos, versiones, RNF, CI o negocio.

## Preparación

Requisitos: macOS/Linux, Python 3 con `fcntl`, Node compatible con el proyecto,
Docker local con Compose y Chrome instalado. Mantener Docker Desktop abierto
y una sesión gráfica disponible. El launcher rechaza Docker remoto.

Desde la raíz, una sola vez si faltan dependencias:

```sh
npm ci --prefix frontend
cd frontend
npx playwright install chrome
```

La instalación de Chrome sólo es necesaria si falta el navegador; no reemplazar
una instalación existente durante la presentación.

## Comandos desde la raíz

| CU | Comando | Recorrido visible |
|---|---|---|
| 07 | `npm --prefix frontend run demo:cu07` | Registro, consentimientos, correo real en Mailpit, confirmación y acceso como comprador. |
| 08 | `npm --prefix frontend run demo:cu08` | Acceso, alternar VENDEDOR/COMPRADOR, consultar sesiones, cerrar sesión y comprobar rechazo del cookie anterior. |
| 09 | `npm --prefix frontend run demo:cu09` | Consultar perfil, editar nombre y promociones, confirmar contraseña, guardar y recargar. |
| 10 | `npm --prefix frontend run demo:cu10` | Contactar desde producto, pregunta del comprador, respuesta del vendedor en otra ventana, recepción/notificación y consulta vinculada a pedido. |
| 11 | `npm --prefix frontend run demo:cu11` | Consultar pedido confirmado, motivo Otro obligatorio, cancelar y verificar estado tras recargar. |

Dentro de `frontend`, usar directamente `npm run demo:cu07`, etc.
Una demo a la vez; el launcher tiene bloqueo entre sus propias ejecuciones.
Chrome visible es el valor predeterminado, con `slowMo=350 ms` y 1,2 segundos para observar cada captura clave. Las pausas son de presentación; las verificaciones usan condiciones reales. Para adaptar ritmo:
`npm --prefix frontend run demo:cu07 -- --slow-ms 600`.
`--headless` sirve únicamente para diagnóstico y no acredita ejecución visual.

## Aislamiento y evidencia

Cada comando crea un proyecto Compose aleatorio `sust-cuXX-...`, redes internas para backend/base y una red de entrada para frontend/Mailpit,
MySQL nuevo en tmpfs y puertos efímeros en 127.0.0.1 para frontend y Mailpit.
No publica MySQL ni el backend. Comprueba Flyway y ausencia de cuentas antes del
seed sintético. Sólo elimina recursos de ese proyecto al finalizar. No ejecuta
`prune`, no toca volúmenes ajenos ni lee `.env` del proyecto.

Los pedidos y cuentas previas son fixtures SQL, no evidencia de checkout ni de
registro por UI salvo el comprador que registra CU07. Las operaciones principales
se ejecutan mediante UI. CU11 persiste una cancelación real en MySQL, pero la
pasarela de devolución implementada es **simulada**: no demuestra dinero devuelto
por una entidad financiera. Tampoco se acredita correo productivo o push externo.

La ruta única impresa por cada ejecución es `artifacts/sustentacion/sust-cuXX-.../`:

- `report.html`: reporte del reporter Playwright con estado y duración.
- `scenarios.json`: resultado por escenario.
- `screens/*.png`: capturas explícitas, con contraseñas, códigos y datos de sesión enmascarados.
- `database-witness.json`: verificación SQL sin credenciales, debe indicar `passed: true`.
- `run.json`: código final de escenario/SQL y si Chrome fue visible.
- `isolation.json`: aislamiento, puertos y versiones Flyway.
- `build.log`, `startup.log`, `playwright.log`: diagnóstico.
- `error.json`: fallo de infraestructura, si ocurre.
- `cleanup.json`: retirada de recursos propios, debe indicar `passed: true`.

El reporte es HTML generado por un reporter Playwright propio que sólo publica
campos permitidos. Se desactivan trazas, vídeos y snapshots automáticos con datos
HTTP sensibles. No se guardan códigos de correo, passwords, cookies ni cuerpos
HTTP. Los correos/nombres usados son sintéticos. No se sobrescriben ejecuciones.
Un estado `passed` de Playwright requiere además testigo SQL y limpieza aprobados
para considerar exitosa la ejecución completa.

Si se cierra la terminal a la fuerza (SIGKILL), Python no puede ejecutar finally.
Consultar `isolation.json` y retirar únicamente el proyecto exacto creado por la
demo, con el mismo archivo Compose y `-p` que aparece en esa evidencia. Nunca
limpiar otros proyectos. Los logs de construcción se escriben al terminar cada
proceso; la primera ejecución puede tardar varios minutos.

## Día de la sustentación

Ejecutar los cinco comandos previamente en el mismo equipo con acceso a Internet
para descargar imágenes/dependencias y conservar caché. Conectar alimentación,
cerrar aplicaciones pesadas y dejar Chrome visible. En CU10 se alternan las
ventanas automáticamente. Revisar reportes, testigos SQL y limpieza, y conservar
la carpeta completa de la ejecución elegida. Cada comando vuelve a crear los
datos para que el registro y la cancelación sean repetibles.

Los resultados de validación de esta tarea se documentan en
`artifacts/sustentacion/validacion-tarea/` sin reutilizar evidencias antiguas.

## Validación de regresión y observaciones

Las diez suites backend seleccionadas terminaron con 148 pruebas aprobadas, cero
fallos/errores/omisiones. Usan MySQL Testcontainers y pruebas MockMvc/unitarias;
este resultado complementa las demos visuales, no las sustituye. No se ejecutó
la suite general completa, k6, ZAP ni workflows de otros integrantes.

Maven ignoró el parámetro `surefire.reportsDirectory` utilizado para intentar
separar los reportes: regeneró los reportes estándar de las diez suites bajo
`backend/demo/target/surefire-reports`. Esto actualizó resultados generados
previos en esa ruta. No se tocaron las carpetas de evidencias archivadas. El
resumen depurado nuevo está en `artifacts/sustentacion/validacion-tarea/`.

Los intentos iniciales detectaron tres fallos de automatización/preparación:
publicación de puertos en redes completamente internas con Docker 29, y un
panel de detalle de producto que debía cerrarse antes de abrir notificaciones,
y un selector de confirmación CU11 ambiguo por varios mensajes `role=status`.
Se adaptaron únicamente la topología de demos y el recorrido CU10. No se
modificó la aplicación para hacer pasar estos recorridos.


## Archivos de la entrega

Creados:

- `frontend/e2e-demo/account-order-flows.ts`
- `frontend/e2e-sustentacion/helpers.ts`
- `frontend/e2e-sustentacion/reporter.ts`
- `frontend/e2e-sustentacion/cu07.spec.ts`
- `frontend/e2e-sustentacion/cu08.spec.ts`
- `frontend/e2e-sustentacion/cu09.spec.ts`
- `frontend/e2e-sustentacion/cu10.spec.ts`
- `frontend/e2e-sustentacion/cu11.spec.ts`
- `docs/ejecucion/demos-cu07-cu11.md`

Modificados:

- `compose.sustentacion.yaml`: red de entrada para publicación loopback en Docker 29.
- `scripts/demos/run.py`: verificación obligatoria del testigo SQL, validación de URLs, señales y topología.
- `frontend/playwright.sustentacion.config.ts`: límites de espera por acción/navegación.
- `frontend/e2e-demo/demo-launcher.spec.ts`: reutilización de funciones CU08 y CU11, preservando el guion conjunto.

Ya existían y se reutilizaron sin modificación: comandos de `frontend/package.json`
y `scripts/demos/seed.sql`. No hay cambios en el repositorio externo.
Los archivos generados nuevos de evidencia están bajo `artifacts/sustentacion/`;
además Maven regeneró sus reportes estándar según la observación anterior.
