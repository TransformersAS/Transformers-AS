# Auditoría y presentación CU-08

Fuente exclusiva: especificación oficial RF-033, RF-036, RF-192, RF-193, RF-194, 23 pasos y A1–A14 proporcionada por la usuaria en el adjunto e8e0673d-03b2-4c67-85b0-ebbc04e3d719. No se buscan archivos externos ni se ejecuta Git. Worktree `Transformers-AS-demos-cu07-cu11`; referencia `feature/demos-cu07-cu11` comprobada por lectura de metadatos.

## Auditoría anterior a la implementación

D0: `frontend/e2e-sustentacion/cu08.spec.ts` llama a `securityFlow` en `frontend/e2e-demo/account-order-flows.ts`: login multirrol, alterna VENDEDOR/COMPRADOR, lista sesiones y logout; comprueba que la cookie anterior recibe401. No revoca otras sesiones, no cambia contraseña y no utiliza recuperación.

Referencias concretas existentes (resultado actual se informará tras ejecutar; existencia no implica aprobación):

- **B**: `backend/demo/src/test/java/com/transformersas/marketplace/auth/SessionAuthenticationTests.java`: `multipleRolesRequireSelectionAndSwitchPermissionsInOnlyThatSession`, `listsOwnSessionsAndRevokesOnlyTheSelectedSession`, `cannotRevokeAnotherAccountsSessionOrAnUnknownSession`, `passwordChangeStoresBcryptAndRevokesOnlyOtherSessionsOfTheAccount`, `wrongCurrentPasswordDoesNotChangeCredentialsOrRevokeSessions`, `recoveryResetsPasswordAndRevokesAllOwnSessionsOnly`, `recoveryRejectsInvalidExpiredAndSupersededTokens`, `recoveryDeliveryFailureStillReturnsGenericResponseAndCommitsHashedToken` y validación/CSRF. MySQL/MockMvc, notificadores sustituidos en esta suite.
- **P**: `PersistentSessionIntegrationTests.java`: `optedInLoginPersistsCookieAndJdbcTimeoutWithoutWeakeningCookieOrSessionFixationProtection`, `bothModesExpireOnServerEvenIfClientReplaysCookie`, `revokeOthersKeepsCurrentSessionAndOtherAccount`, `passwordChangeRevokesOtherPersistentSessionsAndKeepsCurrentPolicy` y política absoluta/JDBC.
- **C**: `CurrentAccountSessionIntegrationTests.java`: desactivación, cambios de roles e invalidación de sesiones reales mediante MockMvc.
- **E**: `EmailVerificationIntegrationTests.java`: correo real de pruebas, rechazo de acceso pendiente y recuperación del envío503.
- **R**: `PasswordRecoverySmtpIntegrationTests.java`: `realMailContainsUsableTokenOnlyHashIsStoredAndConfirmationChangesPasswordAndRevokesSessions`, uniformidad de respuesta, fallo SMTP neutral y tokens inválidos/vencidos/usados con SMTP real de pruebas.
- **T**: `restrictions/AccountRestrictionIntegrationTests.java`: `aRestrictedRoleLeavesTheOpenSessionAndCannotBeChosenAgain`, `aWholeAccountRestrictionBlocksEveryRoleButKeepsTheAccountPage`, `aSingleRoleAccountUnderRestrictionLogsInWithoutThatRoleActive`.
- **U**: `frontend/e2e/account-functional-flows.spec.ts`, `persistent-session.spec.ts`, `revoke-other-sessions.spec.ts`, `session-ended.spec.ts`, `password-recovery-message.spec.ts`, `email-verification.spec.ts`, `access-validation.spec.ts`, `logout.spec.ts`: pruebas UI con respuestas HTTP interceptadas. No acreditan integración visual real.

### Cinco RF

| RF | Implementación real | Prueba previa concreta | Visible D0 | Falta de automatización visual / brecha |
|---|---|---|---|---|
| RF033 | Sí: contraseña actual, BCrypt nuevo, conserva sesión actual y revoca otras propias | B cambio exitoso/actual incorrecta/nueva inválida; P sesiones persistentes | No | Mostrar cambio, contraseña vieja401/nueva204, otra sesión401 y cuenta ajena conservada. |
| RF036 | Sí: metadatos, identificador público SHA256 distinto de credencial, revocación específica/todas las demás | B lista/revoca/aislamiento; P revokeOthers; U sólo simulado | Sólo listado | Dos contextos independientes, revocar y observar401; completar cierre de otras y autorrevocación. |
| RF192 | Parcial frente al texto oficial: login verificado/activo, selector y permisos sí; restricción administrativa global NO bloquea login | B roles/credenciales; C inactividad; T restricción global deja acceso a cuenta | Login y cambio, no rechazo por permisos | Preparar restricciones SQL propias; presentar comportamiento real sin convertirlo en aprobación de bloqueo total. |
| RF193 | Sí: invalida cookie/sesión servidor y vuelve a login | B logoutDeletesCurrentSessionAndOldCookieCannotAuthenticate; P logout ambos modos | Sí | Conservar replay en memoria sin escribir cookie; ampliar testigo de eliminación. |
| RF194 | Sí: neutralidad, SMTP configurado, mecanismo15min de un uso, nueva credencial y revocación de todas las sesiones propias | B recuperación; R correo real/hash/expiración/reutilización | No | Mostrar Mailpit y recuperación real. El Compose común carece de PASSWORD_RECOVERY_FROM: necesita override exclusivo CU08, sin cambiar negocio. |

### 23 pasos principales

| Paso | Implementado | Prueba previa | Visible D0 | Acción / brecha |
|---|---|---|---|---|
| 1 Abrir acceso | Sí | U/B login | Sí | Pausa y paso descriptivo. |
| 2 Correo, clave, mantener sesión | Sí | U persistent-session | Parcial: no opción explicada | Mostrar controles; probar persistencia en complemento. |
| 3 Credenciales | Sí | B login | Sí | Capturas con contraseñas enmascaradas. |
| 4 Mantener sesión | Sí | P/U | No | Cookie persistente/JDBC y restauración real, sin publicar credencial. |
| 5 Confirmar login | Sí | B/U | Sí | POST real204. |
| 6 Validar credenciales | Sí | B invalidCredentials | Sólo válidas | Alternativa de error neutral con ambos tipos de credencial incorrecta. |
| 7 Estado/correo/restricciones | Parcial | B/C/E/T | No rechazos | Inactiva bloqueada; pendiente bloqueada; restricciones administrativas vacían/limitan rol. |
| 8 Restricción completa impide acceso | Parcial/no en sentido literal | T wholeAccountRestriction | No | La restricción ACCOUNT bloquea roles, mantiene login/cuenta; brecha formal explícita. |
| 9 Crear sesión | Sí | B loginPersistsSession | Implícito | Contar sesiones JDBC sin guardar IDs/atributos. |
| 10 Roles/restricciones | Sí | B/C/T | Roles sí | Comprobar API y restricciones preparadas. |
| 11 Un rol activo automático | Sí | B singleRoleIsAutomaticallyActive | No | Cuenta sintética un rol. |
| 12 Varios roles solicitan selección | Sí | B multipleRoles; U | Parcial: no comprueba null inicial | Verificar activeRole=null y selector. |
| 13 Seleccionar rol | Sí | B/U | Sí | Mantener acción real Ionic. |
| 14 Mostrar rol y funciones | Sí | B assertPermissions | Sólo etiqueta | Probes de autorización CU08 y UI condicionada; sin automatizar otros CU. |
| 15 Permisos durante sesión | Sí | B/C | No comprobación | Consultar endpoints propios `/auth/validation/*` con204/403. |
| 16 Cambiar roles de misma cuenta | Sí | B/U | Sí | Comprobar misma sesión/identidad en memoria. |
| 17 Actualizar permisos/restricciones | Sí | B/T | Parcial | Verificar inversión204/403 después del cambio. |
| 18 Abrir seguridad | Sí | U | Sí | Panel de cuenta tras login. |
| 19 Listado/metadatos/sesión actual | Sí | B/P/U | Sólo indicador actual | Dos sesiones, navegador y última actividad, sin publicar IDs. |
| 20 Revocar específica/cerrar demás | Sí | B/P/U | No | Principal: específica. Complemento: todas las demás y autorrevocación. |
| 21 Invalidar seleccionadas | Sí | B/P | No | Segundo contexto401 sin afectar principal ni otra cuenta. |
| 22 Cerrar sesión | Sí | B/P/U | Sí | Cierre visible. |
| 23 Invalidar actual y login | Sí | B/P | Sí | Replay de cookie anterior401, sin registrar cookie. |

### A1–A14

| Alternativa | Implementado | Prueba previa | Visible D0 | Acción / brecha |
|---|---|---|---|---|
| A1 Credenciales incorrectas | Sí, mensaje genérico | B invalidCredentials; U | No | Comparar mensajes de email inexistente/clave incorrecta y ninguna sesión autenticada. |
| A2 Correo pendiente/reenvío | Sí | E loginOnlyRevealsUnverifiedStatusAfterCorrectCredentials, SMTP; U | No | CU08 panel, correo real, confirmar y acceder; no usar registro CU07. |
| A3 Cuenta/rol restringido | Parcial | C inactividad; T restricciones | No | Separar INACTIVA (bloquea) de ACCOUNT (no bloquea login). Restricción de VENDEDOR debe conservar COMPRADOR. |
| A4 Recuperar contraseña | Sí con remitente SMTP configurado | B/R/U | No | Mensaje neutral, buzón, confirmar, vieja401/nueva204, cerrar sesiones propias y conservar ajena. |
| A5 Mecanismo incorrecto/vencido/usado | Sí | B/R/U | No | Token real; SQL sólo expira el token propio; confirmación por UI. |
| A6 Cambiar contraseña | Sí | B/P/U | No | Actual correcta, nueva válida, sesión actual conservada y demás cerradas. |
| A7 Actual incorrecta | Sí | B/U | No | Rechazo403 sin mutación ni revocación. |
| A8 Nueva inválida/confirmación distinta | Sí | B/U | No | Error legible, botón deshabilitado y ausencia de PUT. |
| A9 Expirada | Sí | P/U session-ended | No | Preparar vencimiento JDBC propio; operación UI protegida muestra finalización y exige acceso. |
| A10 Revocada | Sí | B/P/U | No | Principal dos contextos y401 servidor. |
| A11 Error correo | Parcial | E503; R neutral ante fallo; U | No | Verificación sí informa503. Recuperación NO informa fallo concreto para no revelar existencia: brecha literal, no cambiar neutralidad. |
| A12 Varios roles | Sí | B/U | Parcial | Comprobar que inicialmente no hay rol activo. |
| A13 Cambio de rol | Sí | B/U | Sí, sin permisos | Probar permisos actualizados y misma sesión. |
| A14 Función de otro rol | Sí denegación; UI oculta accesos y ofrece selector, no redirección universal | B validación de roles/HEAD; U access-validation | No | Navegar403 al endpoint de validación propio CU08 y seleccionar rol permitido; no ejecutar compra/inventario de otros CU. |

## Diseño posterior a la auditoría

Principal breve: login multirrol, permisos, dos contextos, metadatos, revocación específica, visualización del rechazo, logout y replay401. Extras independientes: `password`, `recovery`, `access`, `restrictions`, `expiration`, `sessions`, `mail`. Configuración/helpers/reporter/fixtures propios CU08; adaptar sólo ramas CU08 del launcher, conservando CU07 y los demás. Override Compose propio añade el remitente de recuperación ausente, sin modificar el Compose común. SQL sólo prepara datos, restricciones y vencimientos; nunca sustituye operaciones de credenciales, roles o revocación. Nuevas evidencias en directorios únicos y hashes de fuentes anteriores conservados.

## Comandos y modalidad de presentación

Desde la raíz del worktree:

```sh
npm --prefix frontend run demo:cu08
npm --prefix frontend run demo:cu08:extras
```

Complementos independientes, cada uno con su proyecto/base nuevos:

```sh
npm --prefix frontend run demo:cu08:extras -- --scenario password
npm --prefix frontend run demo:cu08:extras -- --scenario recovery
npm --prefix frontend run demo:cu08:extras -- --scenario access
npm --prefix frontend run demo:cu08:extras -- --scenario restrictions
npm --prefix frontend run demo:cu08:extras -- --scenario expiration
npm --prefix frontend run demo:cu08:extras -- --scenario sessions
npm --prefix frontend run demo:cu08:extras -- --scenario mail
```

Sólo CU08 presentación utiliza por defecto Chrome visible, viewport1440×1000, ventana solicitada1500×1100, slowMo900ms y pausa2500ms. Ejemplo de ajuste:

```sh
CU08_DEMO_SLOW_MS=1100 CU08_DEMO_PAUSE_MS=3000 npm --prefix frontend run demo:cu08
```

`--slow-ms` tiene precedencia sobre la variable. Las pausas son de exposición: los formularios, respuestas y estados se comprueban antes con condiciones reales. Las repeticiones técnicas a100ms/pausa0 no se presentan como ensayos de legibilidad. No se altera el ritmo de CU07, suites normales, CI o rendimiento.

La principal se centra en autenticación, rol, dos contextos reales independientes, revocación y logout. Los contextos representan sesiones distintas del mismo Chrome, no dos equipos físicos. Sus cookies se comparan sólo en memoria. Las capturas ocultan contraseñas, mecanismos y los identificadores públicos de sesión. No se guardan cookies, storageState, trazas ni vídeo. La consulta SQL devuelve exclusivamente contadores/booleanos/plazos, nunca filas de sesión ni hashes.

Los únicos endpoints de funciones por rol usados para comprobar permisos son los específicos de este CU: `/api/auth/validation/comprador` y `/api/auth/validation/vendedor`. No se ejecutan compras, inventario, perfiles, conversaciones o cancelaciones de otros CU.

## Trazabilidad posterior: automatización nueva

La matriz previa distingue implementación, pruebas existentes y visibilidad en D0. Estas tablas añaden los recorridos reales y el criterio comprobado. “Aprobado” en un escenario de caracterización significa que reproduce el comportamiento observado; **no convierte una brecha en cumplimiento**. Los reportes definitivos y sus duraciones están en el índice de auditoría.

Referencias nuevas:

- **M** principal: capturas `main-01` a `main-08`; testigos `main-no-role`, `main-seller`, `main-buyer`, `main-two-sessions`, `main-after-revocation`, `main-logged-out`, `main-cookie-invalidated`.
- **X-password**: actual incorrecta403; débil/UTF-8/confirmación deshabilitan guardar; cambio204; SQL `credentialChanged=1`; otra sesión401; vieja401/nueva204; control200.
- **X-recovery**: respuesta neutral202 para correo existente/inexistente; Mailpit visible; incorrecto/vencido/sustituido/usado400; válido204; SQL dos sesiones→cero; vieja401/nueva204; cuenta de control200.
- **X-access**: error neutral401, INACTIVA401, pendiente403, reenvío SMTP202, confirmación204 y rol único COMPRADOR.
- **X-restrictions**: VENDEDOR restringido403 y COMPRADOR204; URL real denegada403; login de restricción ACCOUNT204, ambos roles403. Se registra la brecha.
- **X-expiration**: cookie persistente y JDBC604800s, recarga real; cookie normal sin expiración de cliente y JDBC1800s; envejecimiento SQL propio y operación protegida401 con aviso de sesión finalizada.
- **X-sessions**: tres sesiones propias, intento de revocar una ajena404 mediante API, cierre de demás por UI204, dos contextos401, control200, autorrevocación y ausencia de sesión propia.
- **X-mail**: Mailpit propio detenido; verificación503 y aviso visible; recuperación202 neutral sin alerta específica; restauración y nuevo mensaje real recibido.

Los testigos JSON están en `cu08-checks/`, las capturas en `screens/` y los pasos/duraciones en `cu08-steps.jsonl`. Las validaciones negativas de aislamiento mediante API se identifican como tales, no como clics de UI inexistentes.

### RF asociados: resultado posterior

| RF | Cobertura nueva verificable | Resultado funcional |
|---|---|---|
| RF033 | X-password: credenciales, SQL, revocación, control; B compara también hash de cuenta ajena y datos preservados | Implementado y demostrado; control de hash ajeno está en backend, no se publica el hash. |
| RF036 | M revoca una; X-sessions cierra demás/actual, verifica ownership404 y contextos401 | Implementado y demostrado, sesiones/identificadores de gestión reales. |
| RF192 | M selección/cambio y permisos; X-access pendiente/inactiva; X-restrictions aislamiento de rol | Parcial respecto al bloqueo total de restricción administrativa ACCOUNT; no se cambia negocio. |
| RF193 | M logout, SQL cero sesiones y replay401 de cookie anterior en memoria | Implementado y demostrado. |
| RF194 | X-recovery neutralidad/SMTP/cambio/rechazos/revocación; R prueba hash almacenado y cuenta ajena intacta | Implementado y demostrado con remitente del entorno de pruebas configurado. |

### 23 pasos: evidencia nueva individual

| Paso | Evidencia nueva | Alcance / resultado |
|---|---|---|
| 1 | M paso01 | Pantalla única de acceso visible. |
| 2 | M formulario; X-expiration | Correo, contraseña y checkbox mostrados. |
| 3 | M login | Datos sintéticos completados por UI. |
| 4 | X-expiration | Checkbox, cookie persistente y plazos JDBC reales. No se afirma reinicio físico del equipo; se prueba recarga y servidor. |
| 5 | M login | Clic y respuesta204. |
| 6 | M; X-access | Acceso válido y errores401 uniformes. |
| 7 | X-access; X-restrictions | Estado/correo verificados; restricción de rol/global observada. |
| 8 | X-restrictions | Brecha: ACCOUNT no bloquea login, sí bloquea roles. INACTIVA sí rechaza login. |
| 9 | M `main-two-sessions` | Dos sesiones JDBC y cookies distintas, sin publicar valores. |
| 10 | M/X-restrictions | Roles reales y permisos correspondientes. |
| 11 | X-access tras confirmar correo | Un único rol automáticamente activo. |
| 12 | M `main-no-role` | activeRole=null y selector disponible con varios roles. |
| 13 | M selector Ionic | Elección explícita de rol por UI. |
| 14 | M `main-seller/main-buyer` | Etiqueta visible y validación de endpoints autorizados. No se ejecutan otros CU. |
| 15 | M/X-restrictions | Endpoints propios responden204/403 según rol, no sólo etiquetas visuales. |
| 16 | M cambios VENDEDOR→COMPRADOR | Cookie en memoria permanece igual; no se cierra sesión. |
| 17 | M y X-restrictions | Inversión de permisos y rechazo de rol restringido. |
| 18 | M panel seguridad | Sección de cuenta tras autenticación. |
| 19 | M listado | Navegador/última actividad/sesión actual comprobados; identificadores ocultos en evidencias. La descripción de navegador es una pista del User-Agent, no prueba de equipo físico. |
| 20 | M / X-sessions | Revocación específica / cerrar demás por UI. |
| 21 | M / X-sessions | Contextos afectados401 y control/principal conservados cuando corresponde. |
| 22 | M logout | Clic real Cerrar sesión. |
| 23 | M `main-logged-out/main-cookie-invalidated` | Acceso y cookie anterior401, formulario de login visible, cero sesiones propias. |

### A1–A14: evidencia nueva individual

| Alternativa | Recorrido nuevo | Resultado / límite |
|---|---|---|
| A1 | X-access | Email desconocido y clave incorrecta401 con el mismo mensaje, sin sesión autenticada. |
| A2 | X-access | Pendiente403; reenvío por UI, Mailpit y confirmación desde CU08; después login204. No se automatiza registro CU07. |
| A3 | X-access/X-restrictions | INACTIVA bloqueada; rol VENDEDOR restringido sin impedir COMPRADOR. Brecha: ACCOUNT permite identidad/cuenta pero no roles. |
| A4 | X-recovery | Solicitud neutral, correo visible, nuevo secreto válido, contraseña previa rechazada, todas las sesiones propias cerradas. |
| A5 | X-recovery | Incorrecto/vencido/sustituido/usado rechazados; otro mecanismo válido recupera. SQL únicamente envejece el mecanismo propio, no modifica la regla. |
| A6 | X-password | Actual correcta y nueva válida; sesión actual permanece, otra propia se invalida. |
| A7 | X-password | Actual incorrecta403; credencial y sesiones no se modifican. |
| A8 | X-password | Mínimo, máximo UTF-8 y confirmación distinta; mensajes y botón deshabilitado. No se presenta una enumeración universal de contraseñas posibles. |
| A9 | X-expiration | Sesión normal/persistente vencida: servidor401 y aviso visible de volver a autenticarse; política original intacta. |
| A10 | M / X-sessions | Sesión revocada desde otro contexto no puede consultar sesiones; aviso de finalización visible. |
| A11 | X-mail | Verificación sí informa503. Recuperación preserva202 y neutralidad incluso al fallar SMTP: brecha literal de aviso específico; no se debilita privacidad. |
| A12 | M | Varios roles, inicialmente sin selección; selector mostrado. |
| A13 | M | Cambio de rol sin cerrar sesión, con permisos efectivamente actualizados. |
| A14 | M/X-restrictions | Denegación403 y selector disponible en cuenta. El HTTP403 no incluye un botón universal de cambio de rol; no se afirma redirección automática que la aplicación no tiene. |

## Regresiones y conservación

Se ejecutaron128 pruebas backend en nueve suites/selecciones, todas aprobadas sin errores ni omisiones. Incluyen SMTP real de pruebas en R/E; otras usan MockMvc/Mockito y no se cuentan como demostración visual. Las pruebas de aislamiento B verifican además que el hash de una cuenta ajena no cambie.

Las44 pruebas UI existentes de ocho archivos pasaron en27,4s. Usan HTTP simulado y sirven sólo como regresión. No se ejecutan suites generales de otros CU, mediciones k6, ZAP ni CI. Java se ejecuta desde una copia bajo `artifacts/cu08-audit/audit-410b10201944/regression-source`; los reportes nuevos se sanean y los anteriores permanecen intactos.

Se comparan1386 archivos previos protegidos: sólo cambia el launcher autorizado y la prueba principal CU08 dentro de esa lista. `package.json` sólo añade `demo:cu08:extras`. Los archivos CU07, otros CU, código productivo, RNF, workflows, Compose común y lockfile conservan sus hashes. La demo CU07 se volvió a ejecutar con Chrome visible y pasó.

Problemas del runner encontrados y conservados: navegación de Chrome a un403 sin cuerpo, que muestra una página de error HTTP; e interacción demasiado rápida de `locator.check()` con Ionic. Se corrigieron reconociendo únicamente el error HTTP esperado (con comprobación independiente403 real) y esperando `checked` después del clic. No se cambiaron respuestas de negocio, permisos o tiempos de seguridad.

## Resultado final y tiempos

La principal pasó dos veces con Chrome visible a900/2500ms:51,908s y52,069s de recorrido. Los comandos completos tardaron136,446s (primera construcción) y92,995s (caché), incluyendo preparación y limpieza.

El conjunto final de siete complementos (`sust-cu08-e00d74563187`) pasó completo:327,744s de escenarios (5min28s),329,543s de proceso navegador y367,427s con infraestructura/limpieza. Desglose: password66,472s; recovery88,814s; access42,956s; restrictions31,696s; expiration28,689s; sessions45,688s; mail23,429s. Todo a900ms y pausas2500ms.

La reutilización del mecanismo de recuperación se volvió a verificar en `sust-cu08-f5ca8c96df3e` proponiendo una contraseña válida **diferente de la vigente**, para que el rechazo no pueda atribuirse a la regla de no repetir la contraseña actual. Se comprobó además que la credencial legítima recuperada continúa funcionando.

Los siete complementos pasaron por separado con bases nuevas y Chrome visible a100ms/pausa0 (repetición técnica). La expiración se repitió después de corregir la carrera del checkbox. La demo CU07 pasó con su ritmo original900/2500ms y43,086s de recorrido. Todos los proyectos propios terminaron sin contenedores, redes o volúmenes pendientes. Los dos intentos fallidos de automatización se conservaron; no se confunden con brechas de negocio.

Índice final, duraciones individuales, historial y enlaces a HTML/SQL/capturas: `artifacts/cu08-audit/audit-410b10201944/validation.md`. Las matrices completas de cinco RF,23 pasos y A1–A14 están en este documento. No se declara cumplimiento de las diferencias A3/A11 ni se inventa una acción universal de cambio de rol en páginas HTTP403.

## Archivos exactos de la entrega

Modificados (3):

- `scripts/demos/run.py`: soporte y ramas exclusivas CU08, con preservación de los comandos anteriores.
- `frontend/package.json`: únicamente se añade `demo:cu08:extras`.
- `frontend/e2e-sustentacion/cu08.spec.ts`: nueva principal CU08.

Creados (8):

- `compose.cu08-presentation.yaml`
- `scripts/demos/cu08-seed.sql`
- `scripts/demos/cu08-fixture.py`
- `frontend/playwright.cu08-presentation.config.ts`
- `frontend/e2e-sustentacion/cu08-helpers.ts`
- `frontend/e2e-sustentacion/cu08-extras.spec.ts`
- `frontend/e2e-sustentacion/cu08-reporter.ts`
- `docs/ejecucion/auditoria-demo-cu08.md`

No se modificaron otros CU, código productivo, RNF, workflows o dependencias. No se ejecutó Git. Los cambios compartidos se limitan al launcher y al script npm autorizado; hashes y comparación de package documentados en los artefactos.

Para presentar: mantener Docker Desktop abierto y sesión gráfica disponible; ejecutar desde este worktree, no desde el repositorio principal. Ensayar previamente los comandos para disponer de las imágenes en caché. Mostrar primero la principal y elegir complementos individuales si no se necesitan todos; el arranque de cada base nueva añade tiempo fuera del recorrido visible.
