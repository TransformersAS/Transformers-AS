# Guía final de sustentación — CU-07 a CU-11

Worktree: `/Users/sofiamantilla/Documents/GitHub/Transformers-AS-demos-cu07-cu11`. Rama esperada: `feature/demos-cu07-cu11`. Todos los comandos de demostración se ejecutan desde esa raíz. Cada invocación prepara datos sintéticos nuevos, abre Chrome y limpia únicamente su proyecto Docker al terminar. No necesita intervención manual ni introducir credenciales.

Esta guía consolida las cinco auditorías existentes; no amplía funcionalidades ni sustituye sus matrices. Informe de esta validación: [validacion-final-demos-cu07-cu11.md](validacion-final-demos-cu07-cu11.md).

## Preparación

- Docker Desktop/motor Docker local iniciado, con Compose v2 y contexto de socket Unix; el launcher requiere que DOCKER_HOST no esté definido. Puertos loopback efímeros, sin reservar puertos de otros proyectos.
- Sesión gráfica disponible y Google Chrome instalado: las configuraciones usan `channel: chrome`. Chromium headless no sustituye esta sustentación visible.
- Python 3.9 o posterior; los scripts usan biblioteca estándar, `fcntl` y Docker CLI. Entorno validado: macOS, Python 3.14.5.
- Node dentro de `frontend/package.json` (`^20.19.0 || ^22.12.0 || ^24.0.0`) y dependencias instaladas mediante lockfile. Esta validación usó Node 24.8.0/npm 11.6.0.
- Angular runtime/compiler 20.3.35, CLI/build-angular 20.3.43, Ionic 8.8.19 y Capacitor core/cli 7.6.10; no actualizar versiones para presentar.
- Para `CU11 detail` y la conjunta CU11: Java local y los JAR PDFBox/fontbox/pdfbox-io/commons-logging ya resueltos en el repositorio Maven local. El instrumento lee el PDF descargado, sin instalar paquetes. El backend declara Java 21; esta máquina dispone de JDK 24.0.1. Si la caché Maven está vacía, preparar dependencias con el wrapper del módulo antes del ensayo.

Preparación habitual de frontend, respetando el lockfile:

```sh
npm --prefix frontend ci
npm --prefix frontend run build
```

Si Chrome no está instalado, instalar el canal requerido con Playwright antes de presentar:

```sh
npm --prefix frontend exec -- playwright install chrome
```

Sólo si faltan los JAR del verificador PDF, preparar las dependencias ya declaradas del backend (el subshell conserva la raíz de la terminal al terminar):

```sh
(cd backend/demo && ./mvnw -B -DskipTests dependency:go-offline)
```

Las imágenes Docker se construyen automáticamente. La primera construcción puede tardar más por descarga y compilación; ese tiempo se distingue del recorrido visual. No se necesitan servicios productivos de pagos, logística o notificaciones.

## Cinco demostraciones principales, una después de otra

```sh
npm --prefix frontend run demo:cu07
npm --prefix frontend run demo:cu08
npm --prefix frontend run demo:cu09
npm --prefix frontend run demo:cu10
npm --prefix frontend run demo:cu11
```

Esperar la línea `Resultado CUxx: exit 0` y comprobar `cleanup.json` antes de iniciar la siguiente. El launcher impide dos demos propias simultáneas; no quitar su bloqueo ni detener contenedores de otros proyectos.

| CU | Qué verá el profesor |
|---|---|
|07|Registro de comprador con datos y consentimientos; cuenta pendiente, mensaje real en Mailpit, verificación y acceso como COMPRADOR.|
|08|Acceso de cuenta multirrol, selección/cambio de rol, dos sesiones independientes, consulta de sesiones, revocación de la otra y cierre de sesión actual.|
|09|Consulta y edición de nombre, teléfono y nacimiento; promociones/novedades, esenciales habilitadas, contraseña actual, éxito y persistencia tras recargar.|
|10|Comprador y vendedor en ventanas/contextos independientes, foco alternado, contexto de producto, pregunta, avisos de ambos, no leídos/lectura, respuesta e historial tras recarga. La conversación de pedido de dos líneas está en `contexts`.|
|11|Pedido de dos líneas, importes/entrega/historia, Otro obligatorio, bloqueo con explicación vacía, consecuencias, cancelación automática, restitución exacta de inventario, resultado financiero simulado y recarga. El visor PDF opcional se omite; descarga y contenido están en `detail`.|

## Complementos completos

```sh
npm --prefix frontend run demo:cu07:extras
npm --prefix frontend run demo:cu08:extras
npm --prefix frontend run demo:cu09:extras
npm --prefix frontend run demo:cu10:extras
npm --prefix frontend run demo:cu11:extras
```

Ejecutarlos secuencialmente. CU11 extras abre tres ambientes nuevos sucesivos: siete escenarios base con reembolso simulado OK, uno PENDING y uno UNAVAILABLE. El orquestador agrega sus resultados y no posee un proyecto Docker propio.

Para una sustentación corta, mostrar las principales y seleccionar sólo los complementos solicitados. Cada escenario individual también crea una base nueva y tiene tiempo de arranque propio.

```sh
npm --prefix frontend run demo:cu07:extras -- --scenario verification
npm --prefix frontend run demo:cu08:extras -- --scenario recovery
npm --prefix frontend run demo:cu09:extras -- --scenario email
npm --prefix frontend run demo:cu10:extras -- --scenario contexts
npm --prefix frontend run demo:cu11:extras -- --scenario detail
```

| CU | Valores disponibles para `--scenario` |
|---|---|
|07|`validation`, `existing`, `verification`, `smtp`, `backend`|
|08|`password`, `recovery`, `access`, `restrictions`, `expiration`, `sessions`, `mail`|
|09|`validation`, `email`, `preferences`, `blocked`, `closure`, `failure`|
|10|`contexts`, `images`, `states`, `restricted`, `report`, `external`, `failure`, `security`, `concurrency`|
|11|`history`, `detail`, `states`, `race`, `consistency`, `refund-pending`, `refund-error`, `external`, `query`|

CU10 `security` es una comprobación adicional de aislamiento. `concurrency` es regresión funcional breve, no medición RNF-020. En CU11 no hay un escenario aprobado de pedido con pago PENDING: ese estado no pertenece al contrato de pedidos.

## Duraciones reales de la validación final

| Comando | Casos aprobados | Recorridos Playwright | Total con arranque/limpieza |
|---|---:|---:|---:|
|`npm --prefix frontend run demo:cu07`|1|43.320s|94.957s|
|`npm --prefix frontend run demo:cu08`|1|51.683s|92.163s|
|`npm --prefix frontend run demo:cu09`|1|36.164s|76.750s|
|`npm --prefix frontend run demo:cu10`|1|74.283s|114.319s|
|`npm --prefix frontend run demo:cu11`|1|45.379s|85.742s|
|`npm --prefix frontend run demo:cu07:extras`|5|245.144s|286.081s|
|`npm --prefix frontend run demo:cu08:extras`|7|331.033s|372.462s|
|`npm --prefix frontend run demo:cu09:extras`|6|272.629s|313.689s|
|`npm --prefix frontend run demo:cu10:extras`|9|342.418s|383.776s|
|`npm --prefix frontend run demo:cu11:extras`|9|251.724s|373.767s|

Resultado:41/41 casos aprobados (5 principales y 36 complementos); diez comandos sucesivos. Las duraciones corresponden a esta corrida y pueden variar por caché/equipo.

El recorrido mide la suma de duraciones de Playwright. La invocación completa incluye construcción, readiness, navegador, testigos y limpieza. Los tiempos pueden variar entre equipos y cachés; los valores de esta tabla son ejecuciones reales, no estimaciones. En el informe local se conserva además el desglose de cada complemento.

## Ajustar el ritmo

Todas las presentaciones usan Chrome visible, viewport 1440×1000, slowMo de 900 ms y pausas de 2500 ms en resultados importantes. Cada CU tiene variables propias; no alteran suites normales, CI, k6 o RNF:

```sh
CU07_DEMO_SLOW_MS=900 CU07_DEMO_PAUSE_MS=2500 npm --prefix frontend run demo:cu07
CU08_DEMO_SLOW_MS=900 CU08_DEMO_PAUSE_MS=2500 npm --prefix frontend run demo:cu08
CU09_DEMO_SLOW_MS=900 CU09_DEMO_PAUSE_MS=2500 npm --prefix frontend run demo:cu09
CU10_DEMO_SLOW_MS=900 CU10_DEMO_PAUSE_MS=2500 npm --prefix frontend run demo:cu10
CU11_DEMO_SLOW_MS=900 CU11_DEMO_PAUSE_MS=2500 npm --prefix frontend run demo:cu11
```

Por ejemplo, para exposición más lenta de un resultado, aumentar sólo la pausa de ese CU:

```sh
CU11_DEMO_SLOW_MS=900 CU11_DEMO_PAUSE_MS=3500 npm --prefix frontend run demo:cu11
```

Los scripts admiten slowMo entre 0 y 2000 ms y exposición entre 0 y 10000 ms. Las pausas sirven para leer; el éxito se determina por UI, respuestas HTTP, Mailpit/API y MySQL. Un diagnóstico a 100 ms/0 ms o con `--headless` no reemplaza la validación visual del ritmo de sustentación.

## Si falla una ejecución

1. Conservar su identificador `sust-cuXX-...` y la ruta de evidencia. No borrar el intento ni considerar aprobada una invocación con exit distinto de 0.
2. Revisar `report.html`, `scenarios.json` y `playwright.log`; si falló antes de abrir Chrome, revisar `error.json`, `build.log` y `startup.log`.
3. Comprobar Docker Desktop, Chrome, dependencias y, para `CU11 detail`, Java/JAR Maven. Un error de entorno o fixture no equivale automáticamente a una brecha de negocio.
4. Comprobar `cleanup.json`: debe indicar `passed: true` y listas vacías. Si no se limpió, identificar el proyecto exacto desde `isolation.json`; actuar únicamente sobre ese proyecto. No usar limpiezas globales, puertos/bases compartidos ni detener servicios ajenos.
5. Con el entorno disponible y sin otra demo propia ejecutándose, repetir el mismo comando. Se generará una base nueva y un directorio de evidencia distinto. No reducir aserciones, eliminar escenarios o desactivar protecciones para obtener verde.
6. Si el fallo se reproduce, registrar si es una regresión nueva de automatización, un fixture antiguo o una limitación ya documentada. Esta validación no autoriza remediaciones productivas o de otros CU.

En CU11 extras, revisar también el `group-summary.json` y los reportes de cada hijo. El padre sólo aprueba si los tres proyectos terminan correctamente.

## Limitaciones que se deben explicar

| CU | Límite conservado |
|---|---|
|07|El alta anónima con correo existente devuelve 409 y exige autenticación para asociar COMPRADOR; no coincide literalmente con un flujo formal sin error. La cuenta pendiente usa ACTIVA con correo no verificado.|
|08|Restricción global bloquea roles manteniendo login/cuenta; recuperación conserva respuesta neutral ante fallo SMTP; acceso prohibido no ofrece una redirección universal de cambio de rol.|
|09|RF034 parcial: se guardan preferencias y permanece comunicación esencial, pero no existe emisor de promociones/novedades que aplique esas preferencias a envíos.|
|10|Cuatro pruebas antiguas del componente de notificaciones fallaron en su fixture sin MensajeriaService. Reporte por mensaje, no conversación completa; consulta de soporte por API; avisos externos usan adaptador simulado.|
|11|Visor PDF integrado fallido en Chrome: descarga y contenido sí verificados, render no. Paso 18/A9 no representable como pedido PENDING; checkout previo es otro flujo. Suite unitaria antigua Mis pedidos:4/43 aprobadas y 39 fallidas por fixture sin ContactarVendedorComponent.|

Los adaptadores de pagos/reembolsos/notificaciones externos son de prueba. «Resultado simulado» no representa acreditación bancaria. Estados y eventos logísticos preparados como fixtures no acreditan la ejecución de CU03/CU19/CU23/CU24. Las limitaciones se conservan como contexto, no como nuevas tareas de desarrollo.

Auditorías originales: [CU07](auditoria-demo-cu07.md), [CU08](auditoria-demo-cu08.md), [CU09](auditoria-demo-cu09.md), [CU10](auditoria-demo-cu10.md), [CU11](auditoria-demo-cu11.md).

## Evidencias y selección manual para integrar

- Ejecuciones: `artifacts/sustentacion/sust-cuXX-.../` con HTML, capturas protegidas, pasos, JSON de resultados, testigos SQL, aislamiento y limpieza.
- Conjuntas CU11: `artifacts/cu11-groups/group-.../`; sus hijos están en `artifacts/sustentacion/`.
- Validación final: `artifacts/final-demos-validation/validation-2bcd72f30140/` con índice HTML/Markdown, instalación/build/auditorías npm, integridad histórica, pruebas HTTP de protecciones y tabla de ejecuciones.
- Históricos: se conservan todos los directorios de auditorías CU07–CU11 y los intentos fallidos anteriores.

No incluir en Git `artifacts/**`, PDFs/capturas/logs/reportes locales, `frontend/node_modules/**`, `frontend/dist/**`, `frontend/.angular/**`, `frontend/test-results*/**`, `frontend/playwright-report*/**` ni logs del launcher. Las reglas existentes ya excluyen `artifacts/` y los principales directorios generados; no se cambió `.gitignore`.

El informe distingue los dos documentos creados en esta validación, la unión de archivos declarados por las cinco auditorías y la infraestructura común que ya existía antes de ellas. No se consultó Git para determinar qué está staged/tracked o para comparar contra main; esa selección la realiza la usuaria manualmente. No se ejecutan operaciones Git desde estos comandos.
