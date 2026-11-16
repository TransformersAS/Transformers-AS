# Auditoría y presentación CU-09

Fuente: especificación oficial aportada por la usuaria en el adjunto4a21f9d0-0de8-4feb-ac0b-c9cfe459279b. Worktree exclusivo `Transformers-AS-demos-cu07-cu11`, referencia `feature/demos-cu07-cu11` leída en metadatos sin ejecutar Git. No se buscan documentos externos.

## Auditoría anterior a los cambios

D0: `frontend/e2e-sustentacion/cu09.spec.ts`: login, perfil, cambia sólo nombre/promotions, contraseña, guarda, recarga y verifica API. No presenta teléfono/fecha/news, cambio de correo, validaciones, cierre o errores de infraestructura.

Evidencias previas concretas:

- **B** `backend/demo/src/test/java/com/transformersas/marketplace/users/PersonalProfileIntegrationTests.java`: `datosValidosSeGuardanConContrasena`, `fechaFuturaNoModificaDatos`, `datosInvalidosSeRechazan`, `contrasenaIncorrectaNoAplicaCambios`, `correoOcupadoEsRechazado`, `correoPendienteConservaDireccionYAlmacenaHash`, `confirmarCorreoRevocaTodasLasSesiones`, `reenvioSustituyeTokenAnterior`, `tokenVencidoNoCambiaCorreo`, `cierrePermitidoConservaCuentaYPerfilYRevocaSesiones`, `pedidoPendienteCompradorImpideCierre`, `reclamacionPendienteImpideCierre`, `devolucionPendienteImpideCierre`, `cierreConHistoriaNoBorraPedidosYRetiraOfertas`, `preferenciasNoBloqueanNotificacionEsencialReal`, `errorPersistenciaRevierteDatosYPreferencias` y concurrencia/CSRF/aislamiento. MySQL real/MockMvc, envío de cambio de correo Mockito: no prueban Mailpit visual.
- **M** `PersonalProfileMigrationTests.migracionPreservaDatosPreviosYEsRepetible`: migración MySQL.
- **R** `frontend/e2e-real/cu09-profile.spec.ts`: navegador+backend+MySQL+Mailpit, validación futura, contraseña incorrecta, cambio/reenvío/confirmación de correo y cierre. Los resultados de esta auditoría se informan después de ejecutar; no se infieren por existir el archivo.
- No se encontró una suite CU09 de perfil en `frontend/e2e/` (HTTP simulado). No se inventa esa cobertura; se reutilizará R como regresión frontend real, en proyecto propio y a ritmo técnico.

### RF asociados

| RF | Implementado | Prueba previa que comprueba criterio | Visible D0 | Falta / brecha |
|---|---|---|---|---|
| RF031 | Sí: nombre/fecha/email/teléfono, @PastOrPresent, perfil de identidad | B datosValidos, fechaFutura, datosInvalidos, clienteNoPuedeSeleccionarOtraCuenta; R | Sólo nombre | Mostrar los cuatro campos y cambios de nombre/fecha/teléfono, SQL y recarga. |
| RF032 | Sí: contraseña, disponibilidad, mecanismo30min, correo anterior hasta confirmar, revoca sesiones al aplicar | B contrasenaIncorrecta, correoOcupado, correoPendiente, confirmarCorreo, reenvio; R | Sólo contraseña correcta sin cambio de email | Presentar contraseña incorrecta, ocupado, pendiente/reenvío/correo real y confirmación. |
| RF034 | Parcial de extremo a extremo: ambas preferencias se guardan y essentialCommunications=true; esenciales no son configurables | B preferenciasNoRequierenContrasenaYEsencialesPermanecen; preferenciasNoBloqueanNotificacionEsencialReal publica SECURITY real | Sólo promotions y persistencia | Mostrar on/off de ambas y entrega de correo esencial con ambas off. No hay emisor de comunicaciones promocionales/novedades que lea estas preferencias: no se acredita filtrado opcional real. El módulo `promotions` de cupones no es una campaña de comunicación. |
| RF035 | Sí: elegibilidad, identidad, confirmación, estado INACTIVA sin borrar historia; chequeo transaccional | B blockers pedidos/claims/returns y cierreConHistoria; R cierre sin historia | No | Cuentas desechables propias, tres impedimentos y cierre elegible con historia existente conservada. No cerrar cuentas de otros CU. |

### Doce pasos principales

| Paso | Implementado | Prueba previa | Visible D0 | Acción / diferencia |
|---|---|---|---|---|
| 1 Acceder al perfil | Sí | R/B consulta | Sí | Login y apertura con pausa. |
| 2 Mostrar datos y preferencias | Sí | B consulta/R | Parcial: no aserciones de todos los campos | Comprobar cuatro campos y dos preferencias/essential. |
| 3 Editar | Sí | R/D0 | Sí | Mantener UI real. |
| 4 Modificar datos permitidos | Sí | B datosValidos/R | Sólo nombre | Añadir fecha y teléfono. |
| 5 Configurar promociones/news | Sí persistencia | B preferencias/R | Sólo promotions | Ambas on/off, SQL y recarga. |
| 6 Esenciales habilitadas | Sí invariancia; entrega completa de las cinco categorías no demostrada | B publica SECURITY real con opt-out | No explícito | Texto y API true; correo de seguridad real con opt-out. No afirmar entregas pedidos/pagos/claims/returns no ejecutadas. |
| 7 Revisar cambios | Sí | R/D0 | Sí | Pantalla revisión. |
| 8 Confirmar guardar | Sí, confirmChanges requerido | R/D0/B | Sí | Clic real. |
| 9 Identidad para datos sensibles | Sí | B contraseñaIncorrecta/R | Sólo correcta | Se solicita en revisión ANTES del clic final, no después de confirmar como orden literal8/9. Preferencias solas no la exigen. |
| 10 Validar datos | Sí | B y R fecha futura | Sólo válidos | UI requerida/formatos/fecha futura y no mutación. |
| 11 Guardar sin cambio email | Sí, transacción y versión optimista | B datosValidos/versionObsoleta/errorPersistencia | Nombre/promotions | Todos los datos seleccionados y preferencias. |
| 12 Confirmación y perfil actualizado | Sí | R/D0 | Sí | Éxito, valores inmediatos y persistencia tras recargar. |

### Alternativas A1–A10

| Flujo | Implementado | Prueba previa concreta | Visible D0 | Automatización faltante / brecha |
|---|---|---|---|---|
| A1 Inválidos/incompletos | Sí, errores por campo, conserva borrador | B datosInvalidos; R futura y nombre conservado | No | Vacíos/nombre largo/teléfono/email/fecha, otros datos conservados y cero guardados. |
| A2 Cambio correo | Sí | B correoPendiente/confirmar; R | No | Password actual, Mailpit real, antes y después; conserva identidad y cierra sesiones. |
| A3 Correo registrado | Sí,409 EMAIL_ALREADY_REGISTERED | B correoOcupado | No | Rechazo visible sin alterar perfiles propio/ajeno. |
| A4 Pendiente/reenvío | Sí | B reenvio/pendiente/tokenVencido; R | No | Recarga mantiene email previo, reenvío real y código anterior rechazado. |
| A5 Password incorrecta | Sí,403 CURRENT_PASSWORD_INVALID | B contrasenaIncorrecta; R | No | Mensaje y SQL sin cambios. |
| A6 Fecha nacimiento | Sí campo date y @PastOrPresent | B fechaFutura; R | No | Fecha válida modificada, vacía/futura rechazadas. El navegador no acepta texto arbitrario como fecha; no simular escritura inválida por DOM. |
| A7 Solicitud cierre | Sí, consecuencias y10 tipos de blockers | B cierreExigeConfirmacion/cierreConHistoria | No | Consecuencias, identidad y confirmación en cuenta sintética. No ejecutar procesos de otros CU: se preparan por SQL. |
| A8 Pendientes | Sí: ORDERS/PAYMENTS/REFUNDS/CLAIMS/RETURNS/CHECKOUTS/EXTERNAL_PAYMENTS/EXTERNAL_REFUNDS/RESERVATIONS/STORE_INFORMATION | B pedidos/claims/returns y otros chequeos en repositorio | No | Mostrar pedidos, reclamaciones y devoluciones simultáneos. No declarar demostrados todos los diez tipos sólo por existir consultas SQL. |
| A9 Elegible | Sí | B cierreConHistoria/cierrePermitido; R | No | Desactivación UI, sesiones401, login rechazado y historia conservada por conteos SQL. |
| A10 Error guardar | Sí, manejo DataAccess503, transacción y borrador preservado | B errorPersistenciaRevierteDatosYPreferencias y versionObsoleta | No | Indisponibilidad del backend propio: guardado no aplicado, datos anteriores SQL, reintento UI. Fallo a mitad de transacción se mantiene como evidencia backend, no atribuirlo al fallo de red. |

## Decisión posterior a la auditoría

Principal breve con tres datos personales, ambas preferencias, identidad y persistencia. Seis complementos independientes: `validation`, `email`, `preferences`, `blocked`, `closure`, `failure`. Preparación SQL de cuentas, procesos e historia exclusivamente propios; sin ejecutar otros CU. Reutilizar infraestructura del launcher con ramas sólo CU09, sin modificar archivos CU07/CU08. A10 detiene únicamente el backend de su proyecto y lo restaura en finally; no intercepta HTTP. Regresión R reutilizada sin modificar su archivo, en base/Mailpit nuevos. Los hashes iniciales y copias se conservan antes de editar.

## Cobertura después de ampliar la automatización

Las ejecuciones, duraciones y enlaces exactos se registran en el [índice verificable](../../artifacts/cu09-audit/audit-cd4c84e6e58b/validation.md). Las referencias siguientes identifican aserciones concretas, no sólo archivos existentes:

- **P1–P5**: los cinco `test.step` de `cu09.spec.ts`; capturas `main-01-original` hasta `main-05-persistencia`; `cu09-checks/main-persisted.json`, `main-control-unchanged.json` y `main-essential.json`.
- **V**: `validation`; ocho capturas de campo inválido, contraseña requerida/incorrecta y correo ocupado; testigos `validation-no-mutation`, `validation-wrong-password`, `validation-email-occupied`, `validation-control-unchanged`.
- **E**: `email`; pendiente antes/después de recarga, mecanismo inválido/vencido/sustituido, reenvío real y Mailpit, confirmación, sesiones revocadas, login antiguo401 y nuevo204; testigos `email-pending`, `email-expired`, `email-confirmed`, `email-acceptance`.
- **O**: `preferences`; ambas opciones on/off después de recarga y SQL, revisión sin contraseña para preferencias solas y correo de seguridad real con opt-out; testigos `preferences-on`, `preferences-off`, `preferences-essential-delivery`.
- **I**: `blocked`; GET de elegibilidad y tres razones visibles; sin acción de desactivación; testigos `blocked-reasons`, `blocked-account-retained`.
- **C**: `closure`; consecuencias, contraseña y checkbox explícito, POST204, sesiones401 y login rechazado; `closure-before`, `closure-history-preserved`, `closure-control-unchanged` conservan cuenta/perfil/pedido/ítem/historial/reclamación/devolución/reembolso y verifican suspensión de tienda/retirada de oferta.
- **F**: `failure`; backend de este proyecto detenido y restaurado, PUT real fallido, error y borrador en UI, SQL anterior intacto y reintento UI exitoso; `failure-real-http`, `failure-original-preserved`, `failure-retry-persisted`.

### RF: matriz final individual

| Elemento | ¿Implementado? | ¿Prueba automática? | ¿Visible antes → ahora? | Evidencia concreta | ¿Qué falta automatizar? | ¿Brecha funcional? |
|---|---|---|---|---|---|---|
| RF031 | Sí | B/R y P/V reales | Nombre → cuatro datos, edición de tres | P1 muestra cuatro campos; P2/P4/P5 verifican nombre, fecha, teléfono y cuenta control intacta; V fecha futura | No queda pendiente el recorrido solicitado; fechas calendario arbitrarias no se fuerzan sobre input date | No detectada para los datos permitidos |
| RF032 | Sí | B/R, V/E reales | Password correcta → identidad, ocupado, verificación y correo anterior | V403/409 y cero mutación; E Mailpit real, anterior hasta confirmar, nueva dirección después y sesiones revocadas | Reutilización posterior de código consumido queda en B; sección UI desaparece al confirmar | No detectada; verificación real obligatoria |
| RF034 | Parcial de extremo a extremo | B, P/O reales | Promotions → ambas opciones y esenciales explícitas | P persistencia promotions1/news0; O ambas on/off, API essential=true, correo esencial de seguridad recibido con ambas off | Entrega de las otras cuatro categorías esenciales y campañas opcionales reales | Sí: no existe emisor promocional/novedades que consuma estas preferencias; persistir flags no prueba filtrado de envíos |
| RF035 | Sí en recorridos ejecutados | B/R e I/C reales | Ausente → impedimentos y cierre con historia | I motivos ORDERS/CLAIMS/RETURNS; C INACTIVA, conteos históricos conservados, sesiones/login rechazados y cuenta control intacta | Siete tipos adicionales de impedimento no se presentan positivamente | Ninguna detectada en los tres impedimentos y cierre elegible; no se generaliza a los diez tipos |

### Principal: doce pasos, sin agrupar criterios

| Paso | ¿Implementado? | ¿Prueba automática? | ¿Visible antes → ahora? | Evidencia concreta | ¿Qué falta automatizar? | ¿Brecha funcional? |
|---|---|---|---|---|---|---|
| 1 Perfil | Sí | R/P1 | Sí → sí | Login204, apertura y heading de perfil | Nada para este paso | No |
| 2 Datos/preferencias | Sí | B/R/P1 | Parcial → completo | P1 aserciones de cuatro valores y API de ambas opciones/essential | Nada para consulta | No |
| 3 Editar | Sí | R/P2 | Sí → sí | Botón real y campo editable | Nada | No |
| 4 Modificar datos | Sí | B/R/P2 | Nombre → nombre/fecha/teléfono | Campos con tres valores nuevos; E cubre cambio especial de email | Nada en datos seleccionados | No |
| 5 Opcionales | Sí persistencia | B/P2/O | Una → ambas | P2 promotions on/news off; O ambos on y ambos off | Envío opcional real | Brecha RF034 de entrega, no de almacenamiento |
| 6 Esenciales | Invariancia sí; entrega exhaustiva no acreditada | B/P2/O | No explícito → texto/API/correo seguridad | essential=true, sin checkbox para apagarla; O correo real con opt-out | Pedidos/pagos/reclamaciones/devoluciones como cuatro entregas distintas | No se acredita cobertura integral de entregas |
| 7 Revisar | Sí | R/P3 | Sí → sí | Heading revisión y captura de cambios | Nada | No |
| 8 Confirmar | Sí | B/R/P4 | Sí → sí | Clic Confirmar y guardar, PUT200 | Nada | Diferencia de orden con paso9: contraseña en revisión antes de clic final |
| 9 Identidad | Sí | B/R/P3/V | Correcta → requerida, correcta e incorrecta | Campo actual, requerido sin request y403 sin modificar SQL | Nada para contraseña actual | Diferencia de orden8/9; no omisión de identidad |
| 10 Validación | Sí | B/R/P4/V | Sólo válidos → válidos e inválidos | PUT200 principal; V errores por campo y SQL sin mutación | Fechas calendario no representables por input nativo | No detectada en entradas ejecutadas |
| 11 Persistir sin email | Sí | B/P4/P5 | Nombre/opción → tres datos/ambas opciones | API exacta y MySQL updatedData1, promotions1/news0; correo previo intacto | Nada en este recorrido | No |
| 12 Confirmación inmediata | Sí | R/P4/P5 | Sí → sí, ampliada | Status exacto, tres valores inmediatos y tras recarga | Nada | No |

### Alternos: A1–A10 individualmente

| Flujo | ¿Implementado? | ¿Prueba automática? | ¿Visible antes → ahora? | Evidencia concreta | ¿Qué falta automatizar? | ¿Brecha funcional? |
|---|---|---|---|---|---|---|
| A1 | Sí | B/R/V | No → sí | Nombre vacío/largo, teléfono vacío/inválido, correo vacío/inválido, fecha vacía/futura; error del campo y otro dato borrador conservado; SQL original/version0 | No queda pendiente lo seleccionado | No |
| A2 | Sí | B/R/E | No → sí | Contraseña actual, email disponible, Mailpit, anterior+pendiente en API/SQL hasta POST confirm204; login nuevo funciona | Código consumido posterior queda probado en B, no por formulario desaparecido | No |
| A3 | Sí | B/V | No → sí | PUT409 EMAIL_ALREADY_REGISTERED y perfiles propio/control sin cambios, sin solicitud pendiente | Nada en este criterio | No |
| A4 | Sí | B/R/E | No → sí | Email anterior tras recarga y login independiente; reenvío real, código anterior sustituido rechazado, nuevo válido | Nada para pendiente/reenvío | No |
| A5 | Sí | B/R/V | No → sí | Contraseña requerida e incorrecta403, mensaje visible y SQL original/version0 | Nada | No |
| A6 | Sí para fecha válida y futura | B/R/P/V | No → sí | P guarda1995-06-15; V vacío/futuro2999 con error y cero guardado | No se inyectan fechas imposibles por DOM; límites calendario adicionales no se atribuyen a esta demo | Cobertura acotada, sin nueva brecha detectada |
| A7 | Sí | B/R/I/C | No → sí | Consecuencias visibles; elegibilidad consultada; C password y confirmación explícita | Nada en solicitud ejecutada | No |
| A8 | Sí para bloqueos ejercitados | B/I | No → sí | API allowed=false; ORDERS/CLAIMS/RETURNS y tres mensajes UI, sin botón ni clave de cierre; cuenta activa | PAYMENTS/REFUNDS/CHECKOUTS/EXTERNAL_PAYMENTS/EXTERNAL_REFUNDS/RESERVATIONS/STORE_INFORMATION no se ejercitan positivamente | No detectada en tres; no se afirma validación exhaustiva |
| A9 | Sí | B/R/C | No → sí | allowed=true, password y checkbox; POST204, INACTIVA, historia1 por tabla, perfil1, sesiones0, tienda suspendida/producto no activo y control intacto | No se afirma comparación de todo el contenido histórico: se prueban conservación, conteos y referencias reales | No detectada |
| A10 | Sí: manejo de fallo y transacción backend | B/F | No → sí | F PUT504 real con servicio propio detenido, error, borrador retenido y datos previos; servicio restaurado y retry200/SQLversion1. B errorPersistencia verifica rollback atómico | F no provoca fallo a mitad de escritura; esa evidencia pertenece a B con restricción SQL temporal | No detectada; distinguir indisponibilidad de rollback interno |

## Uso y alcance técnico

Ejecutar desde el worktree autorizado:

```sh
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS-demos-cu07-cu11
npm --prefix frontend run demo:cu09
npm --prefix frontend run demo:cu09:extras
npm --prefix frontend run demo:cu09:extras -- --scenario validation
npm --prefix frontend run demo:cu09:extras -- --scenario email
npm --prefix frontend run demo:cu09:extras -- --scenario preferences
npm --prefix frontend run demo:cu09:extras -- --scenario blocked
npm --prefix frontend run demo:cu09:extras -- --scenario closure
npm --prefix frontend run demo:cu09:extras -- --scenario failure
```

Principal y extras usan por defecto Chrome visible, viewport1440×1000, slowMo900ms y exposición2500ms por captura importante. Esas pausas son sólo de exposición: aserciones UI, respuestas HTTP y readiness determinan finalización. Se puede configurar sin tocar suites normales:

```sh
CU09_DEMO_SLOW_MS=600 CU09_DEMO_PAUSE_MS=2000 npm --prefix frontend run demo:cu09
CU09_DEMO_SLOW_MS=100 CU09_DEMO_PAUSE_MS=0 npm --prefix frontend run demo:cu09:extras -- --scenario email
CU09_DEMO_PAUSE_MS=0 python3 scripts/demos/run.py cu09 --regression --slow-ms 100
```

La última orden selecciona R existente sin editarla: no reemplaza pruebas normales ni cambia CI. Backend relacionado: `./mvnw -B -Dtest=PersonalProfileIntegrationTests,PersonalProfileMigrationTests -Djacoco.skip=true test` en copia temporal del mismo módulo, con MySQL Testcontainers y reportes sanitizados; no modifica pruebas ni informes previos del módulo original.

Los fixtures SQL preparan nueve cuentas sintéticas, tres procesos pendientes y una historia resuelta propia. No simulan las respuestas del recorrido ni ejecutan compras/reclamaciones/devoluciones de otros CU. Principal y cada complemento usan cuentas diferentes incluso al correr juntos. Cada invocación crea un proyecto aleatorio, DB nueva sin volúmenes persistentes, backend/MySQL en redes internas y puertos dinámicos loopback para Nginx/Mailpit. A10 detiene sólo su backend, lo restaura en finally y el launcher elimina sólo recursos etiquetados con su proyecto.

Las pruebas nuevas conservan autenticación, sesiones JDBC, CSRF y frontend de producción con sus protecciones. No se alteraron CSP/SRI/nonces ni contratos. Sin interceptaciones HTTP, `page.route`, mocks de UI, trazas, vídeos o dumps de cookies. Capturas enmascaran contraseñas, identificadores de sesión y cuerpo Mailpit/códigos; reportes registran sólo ubicaciones de error y resultados permitidos. La inspección visual de perfil y Mailpit confirma legibilidad y ocultación del mecanismo.

Cobertura previa: nombre/promotions/password/recarga en D0 y pruebas B/M/R más amplias fuera de presentación. Cobertura adicional: teléfono/fecha/news, campos requeridos, identidad rechazada, correo ocupado, pendiente/vencido/reenvío/confirmado, sesiones invalidadas, opciones ambas on/off, correo esencial con opt-out, tres impedimentos, cierre con historia y fallo real/reintento. Las limitaciones RF034, fechas, orden8/9, siete impedimentos y fallo a mitad de transacción permanecen explícitas; no se implementó lógica productiva para ocultarlas.


## Resultados efectivos y archivos entregados

Principal: 37.002s y 35.999s de test visible; invocaciones completas 77.746s y 95.456s. Ambas con900ms/2500ms, viewport1440×1000. Extras juntos: seis aprobados, 271.201s de tests, proceso navegador 272.791s e invocación total 311.940s. El total incluye construir/iniciar/limpiar y no equivale a duración de presentación.

| Complemento | Individual100ms/0ms (s) | Conjunto900ms/2500ms (s) | Resultado |
|---|---:|---:|---|
| validation | 10.305 | 79.179 | Aprobado individual y conjunto |
| email | 18.476 | 66.315 | Aprobado individual y conjunto |
| preferences | 9.305 | 36.875 | Aprobado individual y conjunto |
| blocked | 3.515 | 11.765 | Aprobado individual y conjunto |
| closure | 7.295 | 33.669 | Aprobado individual y conjunto |
| failure | 20.784 | 43.398 | Aprobado individual y conjunto |

Regresión frontend R: aprobada en 9.239s, Chrome visible100ms/0ms. Backend:35/35 sin errores, fallos ni omitidas (34 integración,1 migración; MySQL real/MockMvc y SMTP de cambio de correo simulado en B, como se distinguió antes). CU07 principal: aprobada en 45.641s; CU08 principal: aprobada en 52.285s; ambos conservan ritmo900ms/2500ms.

Se conservan dos intentos fallidos: fixture inicial sin snapshots de entrega obligatorios (corregido sólo en SQL), y primera conjunta con cinco complementos aprobados y uno fallido al rellenar login. Tras añadir espera observable de hidratación de inputs Ionic, email y conjunta posterior aprobaron. No se atribuye ese fallo inicial a una brecha productiva ni se presenta aquella conjunta como aprobada. Limpieza aprobada en los15 proyectos, incluida nueva consulta Docker posterior a todas las ejecuciones.

Tres archivos existentes modificados y siete nuevos; inventario exacto en [files.json](../../artifacts/cu09-audit/audit-cd4c84e6e58b/files.json), con comprobación de1458 hashes iniciales en [scope-check.json](../../artifacts/cu09-audit/audit-cd4c84e6e58b/scope-check.json). No se ejecutó Git ni se buscaron documentos externos.

| Tipo | Archivo | Propósito |
|---|---|---|
| Modificado | `scripts/demos/run.py` | Ramas CU09 para fixture, selección principal/extras/regresión, ritmo y testigo estructural; conserva las rutas de los demás CU. |
| Modificado | `frontend/package.json` | Únicamente agrega demo:cu09:extras; comando principal intacto. |
| Modificado | `frontend/e2e-sustentacion/cu09.spec.ts` | Principal visual con datos, preferencias, identidad, recarga y testigos API/SQL. |
| Nuevo | `scripts/demos/cu09-seed.sql` | Nueve cuentas sintéticas y estados pendientes/históricos aislados. |
| Nuevo | `scripts/demos/cu09-fixture.py` | Testigos SQL y fallo/vencimiento sólo en el proyecto propio. |
| Nuevo | `frontend/playwright.cu09-presentation.config.ts` | Chrome visible1440×1000; selección CU09 sin alterar suites normales. |
| Nuevo | `frontend/e2e-sustentacion/cu09-helpers.ts` | Operaciones UI reales, Mailpit, capturas enmascaradas y evidencias. |
| Nuevo | `frontend/e2e-sustentacion/cu09-extras.spec.ts` | Seis escenarios independientes de validación, correo, preferencias, bloqueos, cierre y fallo. |
| Nuevo | `frontend/e2e-sustentacion/cu09-reporter.ts` | Logs de pasos y duración sin registrar valores reservados. |
| Nuevo | `docs/ejecucion/auditoria-demo-cu09.md` | Matrices previas/finales, criterios, evidencia y límites. |
