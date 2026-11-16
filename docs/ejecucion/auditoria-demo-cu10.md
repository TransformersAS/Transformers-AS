# Auditoría CU-10 — Conversar sobre productos y pedidos

Especificación: adjunto60128aac-7074-46cd-856b-de7a550ddc49 suministrado por la usuaria. Worktree exclusivo `Transformers-AS-demos-cu07-cu11`; rama `feature/demos-cu07-cu11` verificada leyendo metadatos, sin ejecutar Git. Hashes iniciales en `artifacts/cu10-audit/audit-66a2d76589af/baseline.json`. Esta auditoría se redactó antes de editar instrumentos de demo.

## Evidencia anterior y tipos de prueba

- **D0**: `frontend/e2e-sustentacion/cu10.spec.ts`, demo real Angular/Nginx/Spring/MySQL con dos contextos: pregunta de producto, respuesta, recepción por polling, notificación del comprador, enlace a contexto y mensaje de pedido. Sólo350ms slowMo/1200ms exposición; sin aserción explícita de lectura, notificación del vendedor, imágenes, archivo/cierre, restricciones, reporte o errores. Testigo SQL global exige dos contextos,3 mensajes y3 notificaciones. No equivale a cubrir los38 criterios.
- **B**: `Cu10IntegrationTests` (10 métodos): MySQL real/Flyway/transacciones, servicios Spring y MockMvc; proveedor externo Mockito. `productOrderReuseDifferentContextsAndMultiStoreOwnership`; `messagesReadArchiveCloseReopenAndIdempotency`; `invalidImagesEmptyMessagesRestrictionsAndIsolation`; `communicationRestrictedActiveAccountKeepsRealSessionAndReadAccess`; `concurrentStartAndSendStablePagination`; `atomicNotificationAfterCommitExternalFailureAndPersistenceFailure`; `authenticatedEndpointsCsrfRoleAndAttachmentOwnership`; `moderationPreservesMessageAndAttachmentForCu20Support`; `reportUsesCu20PreservesOriginalAndBlocksOwnOrForeignContent`.
- **R**, décimo método B `browserWithRealBackendMysqlAndSimulatedExternalProvider`: lanza los2 tests existentes `frontend/e2e-cu10/conversations.spec.ts` contra servidor Spring de puerto aleatorio y proxy real. Incluye fallo SQL por trigger, imágenes corrupta/válida, lectura, archivo/cierre/reapertura, reporte, persistencia, contexto de pedido con2 productos y aislamiento. Auth por API real, no login visible; proveedor externo Mockito. Su resultado debe ejecutarse, no inferirse del archivo.
- **I**: `Cu10ImagesTests`,3 pruebas unitarias del validador con contenido real JPEG/PNG/WebP y entradas corruptas/renombradas/excesivas. Sin navegador ni DB.
- **C**: `frontend/tests/conversaciones-concurrency.test.cjs`,9 regresiones deterministas: ejecutan métodos TypeScript reales con RxJS y servicio controlado. Cursor propio/remoto, commits tardíos,125 mensajes paginados, deduplicación, cambios A→B→A, lectura desordenada, borradores/idempotencia. NO E2E real ni medición RNF020.
- **N**: `frontend/tests/notificaciones.test.cjs` prueba componente controlado; `frontend/e2e/notificaciones.spec.ts` usa `page.route` con respuestas simuladas. Se distinguen de R/D0 y las nuevas demos reales.
- `scripts/rnf020/functional.cjs` conserva regresión real de6 envíos UI simultáneos y recepción ordenada, pero su entorno y cuenta son RNF020. No se modifica ni repite la campaña de400 mensajes/k6. Se reutiliza su mecanismo breve en un complemento CU10 propio.

## Matriz RF anterior a la ampliación

| RF | Implementado | Prueba automática existente exacta | Visible D0 | Cobertura adicional necesaria | Brecha / límite |
|---|---|---|---|---|---|
|037 Contexto|Sí|B productOrderReuse; R pedido con2 productos|Sí, producto/pedido1 línea|Reutilizar sin duplicar, distintos productos y2 líneas de pedido; verificar comprador/vendedor/contexto SQL|Ninguna detectada en fuentes; falta ejecución nueva|
|038 Texto/imágenes|Sí|B invalidImages; I3; R corrupta/válida|Sólo texto|Imagen válida de ambos participantes, formato/tamaño rechazados y borrador intacto|Validación cliente MIME/tamaño; contenido se valida en servidor|
|039 Historial|Sí en operaciones disponibles|B messagesRead y authenticatedEndpoints PUT/DELETE4xx; R recarga|Parcial, intercambio sin recarga|Recarga de ambos, orden y ausencia de edición/borrado; SQL|No afirmar inmutabilidad frente a administrador SQL; moderación puede ocultar conservando original|
|040 Lectura/estados|Sí|B messagesRead; R lectura/archivo/cierre|No explícito|Unread antes de abrir, readThrough después; archivo reversible sin bloquear envío; cierre impide y reapertura permite|Archivo es por participante y recibir otro mensaje lo restaura automáticamente|
|041 Notificaciones|Sí, interna transaccional y externa después del commit|B atomicNotification; R con externo Mockito|Sólo comprador|Ambas bandejas/enlaces, correlación mensaje-aviso SQL; externo OK/UNAVAILABLE configurado en adaptador simulado existente|No se acredita entrega a un proveedor productivo; expresamente excluido|
|100 Listar propias|Sí|B ownership/roles; R listado|Parcial|Bandeja vendedor con unread, comprador y cuenta ajena404/lista vacía|No confundir seguridad adicional de cuenta ajena con requisito literal|
|101 Contexto visible|Sí|B productOrderReuse; R pedido2 líneas|Sí|Producto exacto de pedido y contexto distinto sin perder historia|Ninguna detectada|
|102 Responder|Sí|B messagesRead; R respuesta|Texto sí|Respuesta visible con imagen permitida y persistencia en ambos|Ninguna detectada|
|103 Restricción|Sí para cuenta ACTIVA con communications_restricted y tienda no ACTIVE|B communicationRestricted...|No|Historial consultable y POST403 por UI; estado SQL propio|UI permite componer e intentar; bloqueo se informa al servidor. Cuenta INACTIVA no mantiene acceso autenticado; no existe estado SUSPENDIDA independiente|
|104 Reporte/evidencia|Sí mediante integración existente CU20|B reportUsesCu20 y moderationPreserves; R reporte|No|Reporte de mensaje ajeno por UI y lectura de evidencia por API de soporte autenticado|No hay acción de reporte de conversación completa en la UI; no implementar CU20 ni su flujo de moderación|

## Matriz de18 pasos antes de ampliar

| Paso | Implementado | Prueba existente | Visible D0 | Adición necesaria | Brecha / límite |
|---|---|---|---|---|---|
|1 Consultar producto/pedido|Sí|R/D0|Sí|Login visible y contexto legible|No|
|2 Contactar vendedor|Sí|R/D0|Sí|Pausa en contexto creado|No|
|3 Elegir producto de pedido múltiple|Sí, botón por línea|R test2/B productOrderReuse|No, fixture1 línea|Pedido propio2 líneas y elección explícita|No|
|4 Identificar vendedor/buscar previo|Sí|B productOrderReuse|Implícito|Reabrir y comparar ID/conteo|No|
|5 Crear si no existe|Sí, índice único contextual|B productOrderReuse/concurrentStart|Sí|SQL de participantes/contexto|No|
|6 Mostrar historia|Sí|R/B messagesRead|Sí|Recarga y ambos historiales|No|
|7 Escribir/imagen|Sí|R/I/B|Texto sí|Imagen permitida|No|
|8 Confirmar envío|Sí|R/D0|Sí|Respuesta HTTP y mensaje real|No|
|9 Validar|Sí|I/B invalidImages/R|Sólo válido|Vacío, MIME/tamaño/contenido inválido|No|
|10 Registrar fecha/hora|Sí|B messagesRead|No aserción|time visible y SQL orden|No|
|11 Pendiente vendedor|Sí|B messagesRead/R|No|Unread UI y API antes de abrir|No|
|12 Avisos internos/externos|Sí|B atomicNotification|Parcial|Vendedor primero, SQL attempts/status|Externo de prueba simulado|
|13 Vendedor consulta/contexto|Sí|R/D0|Sí|Contexto independiente visible|No|
|14 Marcar leído|Sí|B/R/C|No explícito|APIreadThrough/unread y SQL|No|
|15 Respuesta vendedor|Sí|R/D0|Sí|Imagen/respuesta ambos|No|
|16 Avisos comprador|Sí|B/R/D0|Sí interna|Correlación SQL y navegación|Externo simulado|
|17 Continuar intercambio|Sí|R/C|Pregunta/respuesta|Tercer mensaje y concurrencia breve real|No|
|18 Cierre/archivo|Sí, acciones distintas|B/R|No|Complemento de estados con historia conservada|No|

## Alternos A1–A10 antes de ampliar

| Flujo | Implementado | Prueba existente | Visible D0 | Adición necesaria | Brecha / límite |
|---|---|---|---|---|---|
|A1 Existente|Sí|B productOrderReuse|No|Reabrir UI y mismo ID/historia/conteo|No|
|A2 Distintos contextos|Sí|B productOrderReuse/R pedido|Producto y pedido|Dos productos y selección de2 líneas, sin duplicados|No|
|A3 Imagen inválida|Sí|I/B/R|No|MIME no permitido y>5MiB antes del envío, contenido corrupto400 en servidor|No|
|A4 Vacío|Sí botón deshabilitado y API400|B invalidImages|No|Vacío/espacios sin request ni fila|No|
|A5 Restringida/suspendida|Parcial según significado|B communicationRestricted...|No|ACTIVA restringida y tienda restringida con lectura conservada|INACTIVA pierde acceso; SUSPENDIDA no existe como estado de cuenta separado|
|A6 Cerrada|Sí|B/R|No|Historial visible, envío bloqueado, reabrir y enviar|No|
|A7 Archivada|Sí|B/R|No|Fuera de principal, consultable en Archivadas, envío permitido y restauración|No|
|A8 Reportar|Sí mensaje, no botón de conversación entera|B reportUsesCu20/moderationPreserves/R|No|Reporte UI, contenido intacto y soporteAPI|No atribuir reporte de conversación completa|
|A9 Externo falla|Sí mensaje/aviso interno persisten|B atomicNotification/R (Mockito)|No|Adaptador simulado configurado UNAVAILABLE; SQL FAILED/attempts y consulta por vendedor|No proveedor externo real/productivo|
|A10 Persistencia falla|Sí, UI conserva borrador y no agrega mensaje|B atomicNotification y R trigger|No|Fallo SQL real por constraint sólo en DB desechable, conteos intactos, error y reintento|No sustituir con respuesta HTTP falsa|

## Decisión de presentación

Principal con comprador/vendedor en contextos independientes, nombres de cuentas sintéticas que indican rol, foco alternado y capturas con rol en su nombre. Producto, no leídos, avisos de ambos, lectura, respuesta y recarga. El pedido de2 líneas se separa al complemento `contexts` para mantener la principal comprensible.

Complementos previstos: `contexts`, `images`, `states`, `restricted`, `report`, `external`, `failure`, `security`, `concurrency`. Cada uno tendrá comprador/vendedor/productos propios; sólo fixtures iniciales SQL. `security` es comprobación adicional. Se conserva polling productivo de3000ms y código de cursor sin tocar. Proveedor externo de prueba existente: OK en principal y UNAVAILABLE en extras; sin servicios productivos ni interceptaciones. Resultados y duraciones se completarán después de ejecutar.

## Instrumentación añadida y evidencia verificable

[Índice de resultados y enlaces concretos](../../artifacts/cu10-audit/audit-66a2d76589af/validation.md). Los resultados finales se publican allí tras completar las invocaciones; no se da por aprobado un escenario por existir su código.

La principal usa Chrome visible1440×1000,900ms slowMo y2500ms de exposición por captura importante. Se alterna foco entre ventanas independientes. La pestaña identifica COMPRADOR/VENDEDOR y correo sintético, comprobados contra `/api/auth/me`; los pasos y nombres de capturas identifican el rol. El pie real de la aplicación distingue «Mis compras»/«Mi negocio». No se insertan mensajes ni estados ficticios en la UI. Sólo el título de la pestaña se rotula como instrumento de presentación.

**P1–P6** corresponden a los seis pasos de la principal. **X** contexts, **I2** images, **E** states, **L** restricted, **R2** report, **F1** external, **F2** failure, **S** security, **C2** concurrency. Cada testigo mencionado está en `cu10-checks/` de su propia ejecución. Logs de pasos en `cu10-steps.jsonl`, resultado efectivo en `scenarios.json`, modo/ritmo en `cu10-presentation.json`, total en `cu10-total.json` y limpieza en `cleanup.json`.

### RF: cobertura adicional comprobable

| RF | Principal ampliada / complemento | Aserción y evidencia exacta añadidas | Límite restante |
|---|---|---|---|
|037|P2/X|P2 comprueba buyerId/sellerId/productId y orderId null; X `contexts-reused` threads1/messages1; `contexts-independent` threads4/productThreads2/orderThreads2/messages4, elige las2 líneas reales del pedido|El pedido se demuestra en X, no se afirma dentro de principal|
|038|P3/P5/I2|Pregunta y respuesta reales; I2 ambos participantes envían PNG sintética visible CU10; `images-valid-persisted` images2/messages2; carga real decodificada en ambos DOM; `images-empty`, `images-invalid-client`, `images-invalid-server` sin filas|PNG demostrada; JPEG/WebP quedan en I existente del validador, no en presentación|
|039|P6/E/S|Ambos DOM iguales al historial API tras recargar; IDs y fechas ordenados; `main-final` messages3; `states-before`/`states-archived` mismo historyDigest; S PUT/DELETE con CSRF4xx y digest sin cambios|No se modifica la política existente de moderación; no se afirma protección contra administrador SQL|
|040|P3/P4/E|Vendedor «1 sin leer» antes de abrir; API unread0/readThrough>0 después; `main-final` sellerReadThrough; E archivo no cerrado permite enviar, restaura; cierre deshabilita ambos y reapertura permite nuevo mensaje; `states-final` closed0/buyerArchived0/messages3|Archivo por participante; mensaje entrante puede restaurar automáticamente al destinatario|
|041|P4/P5/F1|Dos bandejas y enlaces reales; `main-final` messages3/notifications3/notificationLinksCorrect3/externalSent3; F1 externalFailed1/attempts>0 y vendedor consulta aviso/mensaje|Externo es el adaptador simulado existente OK/UNAVAILABLE, no un proveedor productivo ni entrega externa de red|
|100|P3/P6/S|Bandejas independientes y unread visible; S lista ajena vacía y GET detalle/historia404; `security-extra`|S es seguridad adicional, diferenciada de RF100 literal|
|101|P2/P4/P5/X|Contexto exacto al abrir desde catálogo y desde notificación; X pedidoId y productoId correctos en cada línea, historias independientes|No se ejecuta compra ni otro CU: pedido preparado por SQL|
|102|P5/I2|Respuesta vendedor recibida por comprador; I2 vendedor adjunta PNG válida visible en ambas partes|Sin funcionalidades nuevas de negocio|
|103|L|Cuenta ACTIVA con flag de restricción conserva sesión e historia; POST403 MESSAGE_RESTRICTED, borrador intacto; `restricted-before`/`restricted-denied` mismo digest/messages2/notifications2; `restricted-scope`|Botón permite intentar y el servidor bloquea; INACTIVA no equivale a una cuenta restringida con derecho de lectura|
|104|R2|Formulario existente radica reporte; contenido original permanece; soporte autenticado GET caso200, contentId y atributo conversationId coinciden; `report-persisted` reports1/messages1 y `report-support`|Reporte de mensaje; soporte se comprueba por API, no se simula pantalla ni modifica CU20; sin reporte de conversación completa|

### Dieciocho pasos: evidencia añadida, individualmente

| Paso | Automatización añadida / evidencia | Ubicación visual |
|---|---|---|
|1|P1 login de ambas cuentas, P2 consulta catálogo|Principal comprador|
|2|P2 Contactar al vendedor, POST real200|Principal comprador|
|3|X exige2 botones de contacto y elige cada producto; IDs pedidos/productos distintos|Complemento contexts|
|4|X reabre mismo producto, compara mismo ID y SQL threads1|Complemento contexts|
|5|P2 asocia comprador/vendedor/producto; X sólo4 contextos ante5 aperturas|Principal y contexts|
|6|P4 historial vendedor, P6 recarga ambos; igualdad con API|Principal ambos|
|7|P3/P5 texto; I2 archivo PNG válida visible240×140|Principal e images|
|8|P3/P5 botón Enviar, respuesta200 y borrador limpio|Principal ambos|
|9|I2 espacios deshabilitan; MIME/tamaño rechazados antes de enviar; PNG corrupta400 sin fila|Complemento images|
|10|P3 time visible, P6 IDs/createdAt ordenados y testigo SQL|Principal|
|11|P3 detalle vendedor unread1 y bandeja «1 sin leer» antes de abrir|Principal vendedor|
|12|P4 abre aviso vendedor; main-final vínculo correcto y despacho externo simulado SENT|Principal vendedor/SQL|
|13|P4 enlace abre producto/contexto e historial comprador|Principal vendedor|
|14|P4 API unread0/readThrough>0 y captura de bandeja sin pendiente|Principal vendedor/API|
|15|P5 respuesta vendedor mediante UI|Principal vendedor|
|16|P5 notificación comprador enlaza misma conversación y contiene respuesta|Principal comprador|
|17|P6 tercer mensaje, ambos historiales recargados; C2 seis envíos simultáneos|Principal y concurrency|
|18|E archivo permite seguir; cierre bloquea; restauración/reapertura conservan historia|Complemento states|

### A1–A10: evidencia añadida, individualmente

| Flujo | Prueba añadida | Aserción concreta / archivo | Pendiente o brecha |
|---|---|---|---|
|A1|X contexts|Mismo ID y mensaje tras reabrir; contexts-reused threads1|Nada del recorrido seleccionado|
|A2|X contexts|2 contextos producto y2 de pedido con mismo vendedor; contexts-independent|Otros vendedores quedan en B multiStoreOwnership|
|A3|I2 images|MIME text/plain y PNG>5MiB rechazados antes del envío; borrador conservado; contenido corrupto400; SQL0|No se presentan todos los formatos/límites del validador en navegador|
|A4|I2 images|Vacío/espacios: botón deshabilitado y messages0/notifications0|API400 de vacío queda en B; no se fuerzan clics sobre control deshabilitado|
|A5|L restricted|ACTIVA restringida lee2 mensajes, envío403, digest y conteos intactos|No existe SUSPENDIDA como estado; INACTIVA pierde acceso. No se declara el alterno entero para cuentas inactivas|
|A6|E states|Ambos controles de envío deshabilitados al cerrar; historia2 visible; reapertura y tercer envío200|Sin cambiar lógica de autorización|
|A7|E states|Archivo retira de principal, aparece en Archivadas, permite enviar y restaurar|No se confunde archivo con cierre|
|A8|R2 report|Radicado UI, reports1; original y conversationId disponibles a soporteAPI200|Sin botón de reporte de conversación completa ni modificación CU20|
|A9|F1 external|Adaptador existente UNAVAILABLE, externalFailed1 y attempts>0; aviso interno y mensaje consultables por vendedor|Simulación explícita del proveedor, no mock HTTP del frontend/backend|
|A10|F2 failure|Constraint SQL sólo propia, POST500, alerta y borrador retenido; sin mensaje falso; digest previo/conteos intactos; retirar constraint y reintentar200, sólo un nuevo mensaje|B además provoca fallo después de insertar mensaje, al guardar notificación, para probar rollback transaccional|

## Comandos exactos

Desde el worktree autorizado:

```sh
npm --prefix frontend run demo:cu10
npm --prefix frontend run demo:cu10:extras
npm --prefix frontend run demo:cu10:extras -- --scenario contexts
npm --prefix frontend run demo:cu10:extras -- --scenario images
npm --prefix frontend run demo:cu10:extras -- --scenario states
npm --prefix frontend run demo:cu10:extras -- --scenario restricted
npm --prefix frontend run demo:cu10:extras -- --scenario report
npm --prefix frontend run demo:cu10:extras -- --scenario external
npm --prefix frontend run demo:cu10:extras -- --scenario failure
npm --prefix frontend run demo:cu10:extras -- --scenario security
npm --prefix frontend run demo:cu10:extras -- --scenario concurrency
```

Ritmo configurable, exclusivamente presentación:

```sh
CU10_DEMO_SLOW_MS=600 CU10_DEMO_PAUSE_MS=2000 npm --prefix frontend run demo:cu10
CU10_DEMO_SLOW_MS=100 CU10_DEMO_PAUSE_MS=0 npm --prefix frontend run demo:cu10:extras -- --scenario concurrency
```

`security` es adicional de aislamiento e integridad; `concurrency` es regresión funcional breve, no medición RNF020. Las pausas sirven para exposición, no para decidir éxito: UI, HTTP, polling de aserciones y MySQL determinan los resultados. No se cambia polling productivo3000ms ni métricas/escenarios RNF020.

Regresiones existentes ejecutadas con fuente original conservada:

```sh
# frontend/
node --test tests/conversaciones-concurrency.test.cjs
node --test tests/notificaciones.test.cjs
# Copia temporal aislada del módulo backend/demo, con frontend compilado de la misma fuente:
./mvnw -B -Dtest=Cu10IntegrationTests,Cu10ImagesTests -Djacoco.skip=true test
```

El método B de navegador ejecuta R existente sin editar sus2 tests, con proxy y servidor Spring reales, MySQL Testcontainers, puerto local14310 comprobado libre y proveedor externo Mockito. La copia temporal del frontend conserva sus dependencias existentes por enlace de lectura; compilación/reportes se escriben sólo en staging propio. Tras extraer XML/resumen sanitizados, se retira staging y no se sobrescriben evidencias anteriores.

## Brechas y fallos de pruebas que deben conservarse explícitos

1. RF103/A5 sólo se acredita para cuenta ACTIVA restringida para comunicación. `AccountStatus` tiene ACTIVA/INACTIVA; INACTIVA no mantiene sesión de consulta. La UI comunica la prohibición al intentar enviar; no deshabilita preventivamente por flag.
2. RF104/A8 acredita reporte de mensaje y evidencia asociada a conversación, no reporte de conversación completa. Consulta de soporte autenticado real por API; no se añade ni altera CU20/CU21.
3. RF041/A9 usa adaptador externo simulado existente, con estados y solicitudes de despacho reales del backend. Se excluyen proveedores productivos y no se afirma entrega de red externa.
4. Las4 pruebas antiguas de `tests/notificaciones.test.cjs` fallaron en su fixture al inicializar la dependencia MensajeriaService, antes de las aserciones del comportamiento. Archivo, producto y suite conservados sin cambios. No se presentan como aprobadas. La principal y R prueban notificaciones reales; las9 regresiones del cursor de conversaciones sí aprobaron.
5. Primera conjunta técnica:8/9 pasaron; failure falló por expectativa incorrecta409 frente a500 real de la constraint. Se corrigió sólo la aserción de demo y se conservó el intento fallido. A10 individual posterior comprobó500, borrador, conteos, digest y reintento200.

Los archivos de la corrección de concurrencia, RNF020, frontend productivo, backend, CU07/CU08/CU09/CU11, workflows y dependencias quedan protegidos por hashes iniciales. Se permiten únicamente las ramas CU10 del lanzador compartido y el script adicional de package.json, más instrumentos propios CU10. No se ejecutó Git ni se buscaron Excel/documentos externos.

Las E2E con respuestas simuladas de `frontend/e2e/notificaciones.spec.ts` se inspeccionaron para clasificar la evidencia, pero no se ejecutaron en esta auditoría. R existente sí se ejecutó mediante B, con su configuración normal de Chrome headless y autenticación API; todas las presentaciones CU10 usan Chrome visible y login UI. El test JUnit de navegador registra éxito sólo cuando el proceso Playwright completo devuelve0; su evidencia se conserva en XML, sin publicar el reporte detallado hijo ni posibles datos de sesión.


## Resultados finales efectivos

Principal aprobada dos veces en Chrome visible: 76.544s y 74.486s de test; invocaciones completas 173.305s y 115.640s. Ritmo900ms/2500ms, viewport1440×1000. Los9 complementos aprobaron individualmente100ms/0ms y juntos900ms/2500ms. Conjunta: 342.362s de tests, 344.973s de proceso navegador y 383.805s incluyendo construir/iniciar/limpiar. No se confunde arranque con presentación.

| Complemento | Individual100/0 (s) | Conjunto900/2500 (s) | Resultado |
|---|---:|---:|---|
| contexts | 9.531 | 58.297 | Aprobado en ambas modalidades |
| images | 11.950 | 54.206 | Aprobado en ambas modalidades |
| states | 11.301 | 47.414 | Aprobado en ambas modalidades |
| restricted | 7.500 | 30.960 | Aprobado en ambas modalidades |
| report | 10.661 | 33.177 | Aprobado en ambas modalidades |
| external | 6.062 | 29.150 | Aprobado en ambas modalidades |
| failure | 7.119 | 30.407 | Aprobado en ambas modalidades |
| security | 7.265 | 28.393 | Aprobado en ambas modalidades |
| concurrency | 8.097 | 30.358 | Aprobado en ambas modalidades |

Backend CU10:13/13 aprobadas sin fallos/errores/omitidas:10 integración y3 validador de imágenes. El método de navegador de integración completó los2 E2E existentes sin editar su fuente; evidencia JUnit/XML y resumen en el índice. Concurrencia del componente:9/9 aprobadas; concurrencia real:6 mensajes en3 rondas, ambos DOM iguales al historial servidor,0 duplicados/omisiones. La corrección de cursor permanece idéntica a su hash inicial.

Resultado auxiliar pendiente: las4 pruebas antiguas del componente de notificaciones fallan por fixture sin MensajeriaService. Se conservan log/resultado y no se modifica esa suite genérica. Por ello no se declara que todas las pruebas ejecutadas aprobaron.

| Regresión demo conservada | Test visible(s) | Resultado |
|---|---:|---|
| CU07 | 44.669 | Aprobada900ms/2500ms |
| CU08 | 53.593 | Aprobada900ms/2500ms |
| CU09 | 35.803 | Aprobada900ms/2500ms |

MySQL conjunta:22 cuentas sintéticas,20 productos,1 pedido y22 mensajes con sus22 notificaciones de conversación. El pedido es fixture, no compra ejecutada. Cada escenario comprueba además sus propios participantes/contextos/estados mediante API y testigos SQL; el testigo global no se presenta como sustituto de esas aserciones. Limpieza validada en los16 proyectos, incluido el intento fallido, y consulta Docker posterior sin recursos propios.

Alcance final:3 archivos existentes modificados y8 nuevos de CU10. Los1476 hashes iniciales permiten verificar el resto; package.json sólo agrega un comando. Ningún archivo productivo, corrección de concurrencia, otro CU, RNF, workflow o dependencia fue modificado. No se ejecutó Git ni se eliminaron informes anteriores.

| Tipo | Archivo | Propósito |
|---|---|---|
| Modificado | `scripts/demos/run.py` | Ramas CU10: fixture propio, extras/filtro, ritmo, configuración y testigo SQL; conserva opciones anteriores. |
| Modificado | `frontend/package.json` | Sólo añade demo:cu10:extras; principal y comandos anteriores intactos. |
| Modificado | `frontend/e2e-sustentacion/cu10.spec.ts` | Principal visible con ambos roles, notificaciones, lectura e historial. |
| Nuevo | `compose.cu10-presentation.yaml` | Configura únicamente el adaptador externo simulado existente para CU10. |
| Nuevo | `scripts/demos/cu10-seed.sql` | 22 cuentas sintéticas,10 tiendas,20 productos y pedido propio con2 líneas. |
| Nuevo | `scripts/demos/cu10-fixture.py` | Testigos SQL, restricción propia y constraint de fallo en DB desechable. |
| Nuevo | `frontend/playwright.cu10-presentation.config.ts` | Chrome visible1440×1000 y selección exclusiva principal/extras. |
| Nuevo | `frontend/e2e-sustentacion/cu10-helpers.ts` | UI real de ambos roles, capturas protegidas, PNG visible y evidencias. |
| Nuevo | `frontend/e2e-sustentacion/cu10-extras.spec.ts` | Nueve escenarios independientes, seguridad adicional y concurrencia breve. |
| Nuevo | `frontend/e2e-sustentacion/cu10-reporter.ts` | Pasos descriptivos y tiempos sin valores de credenciales/sesiones. |
| Nuevo | `docs/ejecucion/auditoria-demo-cu10.md` | Matrices RF/18 pasos/A1–A10, cobertura, comandos, límites y resultados. |
