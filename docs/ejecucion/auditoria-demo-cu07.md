# Auditoría y presentación CU-07

Fuente de requisitos: especificación oficial proporcionada directamente por la usuaria (adjunto 319edb90-d26b-4805-8fff-ab5afddf1a87). No se requiere el Excel. Alcance exclusivo del worktree `Transformers-AS-demos-cu07-cu11`, rama verificada mediante lectura de metadatos `feature/demos-cu07-cu11`; sin comandos Git.

## Auditoría previa a los cambios

Se inspeccionaron `RegistroCompradorComponent`, `RegisterBuyerAccount`, `EnableBuyerRole`, `VerifyAccountEmail`, migraciones V31/V45, pruebas CU07 backend, UI simulada, UI real y demo. Las pruebas externas se consultaron sólo como referencia; no se modifican ni se consideran una ejecución actual.

Referencias de pruebas existentes:

- **B**: `backend/demo/src/test/java/com/transformersas/marketplace/users/BuyerRegistrationIntegrationTests.java`. MySQL Testcontainers y MockMvc; correo sustituido por Mockito. Métodos: `registersProfileConsentAndOnlyBuyerThenVerifiesAndLogsIn` (B1), `smtpFailureKeepsAccountAndResendReplacesExpiredInvalidAndUsedTokens` (B2), `requiredFieldsAndConsent`/`invalidFormatsAndPasswords` (B3), `enablePreservesAccountProfileCredentialsAndActiveRoleAndIsIdempotent` (B4), `concurrentRegistrationAndRoleEnablingDoNotDuplicateRows` (B5), `inactiveAndPendingAccountsCannotEnableAndAlreadyBuyerNeedsNoNewConsent` (B6), `providerTimeoutKeepsPendingRegistrationRecoverable` (B7).
- **U**: `frontend/e2e/registro-comprador.spec.ts`, tres pruebas con HTTP simulado: corrección de campos/verificación inválida (U1), fallo SMTP/reenvío (U2), habilitación (U3). No acreditan integración real.
- **R**: `frontend/e2e-real/buyer-registration.spec.ts`: registro/SMTP/login reales (R1), habilitación sobre cuenta existente (R2).
- **D0**: `frontend/e2e-sustentacion/cu07.spec.ts` antes de esta tarea: únicamente recorrido principal. Copia previa preservada en `artifacts/cu07-audit/audit-6ddd44e523c8/before/`. La evidencia histórica de dos ejecuciones exitosas fue reportada en la entrega anterior; no se presenta como una ejecución nueva de esta auditoría.

“Prueba existente: sí” identifica una aserción concreta en código. Su resultado actual se informa por separado en la validación final; no se presume éxito por la existencia del archivo.

## RF-190 y RF-191 (criterios individuales)

| Criterio | Implementado | Prueba existente | Visible en D0 | Evidencia / acción necesaria |
|---|---|---|---|---|
| RF190.1 Alta nueva y datos/consentimientos | Sí | Sí: B1/B3/R1 | Sí, datos válidos | D0 llena formulario, observa mensaje y comprueba `/auth/me`; añadir testigos de perfil, consentimiento y estado pendiente. |
| RF190.2 Añadir COMPRADOR a misma cuenta | Sí; matiz A1 | Sí: B4/R2/U3 | No | B4 compara identidad, perfil, hash y rol activo; presentar habilitación con cuenta existente real. |
| RF190.3 Aviso si ya es comprador | Sí | Sí: B4/B6/R2 | No | Plantilla `hasBuyer` y aserción de aviso R2; presentar aviso y ausencia de nueva alta. |
| RF191.1 Mecanismo válido verifica y habilita | Sí, con representación técnica descrita abajo | Sí: B1/R1 | Sí | D0 observa SMTP y confirma código; hacer visible la consulta al buzón y la transición antes de login. |
| RF191.2 Correo verificado conservado al añadir rol | Sí | Sí: B4 (`before` igual, `verifyNoInteractions(notifier)`) | No | Presentar A1 y comprobar que no se emite correo ni token nuevo. |
| RF191.3 Rechazo de incorrecto/vencido/usado | Sí | Sí: B1/B2; U1 sólo inválido simulado | No | Añadir complementos reales separados: código incorrecto, sustituido, vencido por fixture y reutilizado. |

## Los 17 pasos del flujo principal

| Paso | Implementado | Prueba existente | Visible en D0 | Evidencia y acción |
|---|---|---|---|---|
| 1 Acceder a crear cuenta | Sí, acceso combinado | Sí R1 | Sí | Botón «Crear cuenta de comprador / Habilitar COMPRADOR». |
| 2 Seleccionar comprador | Sí, mismo botón; no selector aparte | Sí R1 | Sí, combinado con 1 | No inventar una pantalla de selección inexistente. |
| 3 Mostrar formulario | Sí | Sí R1/U1 | Sí | `app-registro-comprador`; añadir pausa inicial. |
| 4 Nombre/fecha/correo/teléfono | Sí | Sí B1/B3/R1 | Sí | Inputs y persistencia B1; añadir testigo SQL de datos sintéticos. |
| 5 Contraseña/confirmación | Sí | Sí B1/B3/R1/U1 | Sí, caso válido | Complementos para errores; nunca mostrar secretos en artefactos. |
| 6 Términos y privacidad | Sí, versionados | Sí B1/B3/R1 | Sí | Dos consentimientos obligatorios; ampliar evidencia SQL. |
| 7 Confirmar creación | Sí | Sí R1 | Sí | Clic UI y POST real 201. |
| 8 Validar campos/formato | Sí | Sí B3; validación UI externa existente | Sólo aceptación de valores válidos | Mostrar errores en extras; D0 no demostraba rechazos. |
| 9 Comprobar cuenta existente | Sí, matiz A1 | Sí B2/B5/R2 | No; sólo correo nuevo | Presentar respuesta real 409 con orientación y posterior autenticación/habilitación. |
| 10 Reglas de contraseña | Sí: mínimo 12 puntos de código/máximo 72 bytes UTF-8 | Sí B3 y prueba Unicode | Sólo contraseña válida | Extras para límites y confirmación. |
| 11 Cuenta pendiente | Sí operacionalmente | Sí B1/B6 | Mensaje, sin SQL intermedio | Se guarda `status=ACTIVA`, `email_verified_at=NULL`; no es un estado enum PENDIENTE. El login se rechaza hasta verificar. |
| 12 Solicitar correo | Sí | Sí R1; B1 no prueba SMTP | Sí en backend, invisible el buzón | D0 consulta Mailpit por API; añadir vista real del mensaje en ventana visible. |
| 13 Mostrar mensaje de envío | Sí | Sí R1/U1 | Sí | Texto exacto en `role=status`; pausa de lectura. |
| 14 Acceder al mecanismo recibido | Sí | Sí R1 | Parcial: extracción API no visible | Mostrar buzón real y mensaje, con código enmascarado sólo al guardar capturas. |
| 15 Validar mecanismo | Sí | Sí R1/B1/B2 | Sí, código válido | Confirmación real 204, extras rechazos. |
| 16 Activar cuenta | Sí operacionalmente | Sí B1/R1 | Implícito por login | Añadir testigo `email_verified_at` antes/después y pantalla login. |
| 17 Dirigir al acceso y entrar | Sí | Sí B1/R1 | Sí | Diálogo login y `/auth/me` COMPRADOR. No demostrar otras funciones de CU08. |

## Alternativas A1–A8

| Alternativa | Implementado | Prueba existente | Visible en D0 | Brecha / acción |
|---|---|---|---|---|
| A1 Cuenta existente y ya comprador | Parcial respecto a «no tratar como error» | Sí B4/B6/R2/U3 | No | El alta anónima existente devuelve **409 ACCOUNT_ACCESS_REQUIRED** y se muestra como alerta. Sí existe «Ya tengo cuenta», autenticación y habilitación sin duplicar. Documentar diferencia; no cambiar negocio. |
| A2 Contraseña inválida | Sí | Sí B3 y validación UI externa | No | Presentar mínimo y máximo UTF-8; comprobar que no se crea cuenta. |
| A3 Confirmación distinta | Sí | Sí B3/U1/UI externa | No | Presentar error y conservación de datos. |
| A4 Sin términos/privacidad | Sí | Sí B3/UI externa | No | Comprobar ambas aceptaciones por separado y ninguna alta. |
| A5 Pendiente | Sí operacionalmente | Sí B1/B6/UI externa | Sí, mensaje; sin rechazo login visible | Presentar rechazo de acceso y testigo SQL pendiente. |
| A6 Reenvío | Sí | Sí B2/U2 | No | Enviar correo real por UI; comprobar código nuevo y anterior sustituido. |
| A7 Inválido/vencido/usado | Sí | Sí B1/B2; U1 simulado sólo inválido | No | Expirar únicamente token de cuenta sintética mediante SQL, nunca saltarse verificación. Confirmar rechazo y no activación; recuperar con nuevo envío. |
| A8 Error y no duplicados | Transacción/uniquidad implementadas; errores arbitrarios no verificados | Sí B2/B5/B7; U2 sólo SMTP simulado | No | Demostrar 409/reintento sin duplicados y fallo SMTP real recuperable. La indisponibilidad SMTP conserva cuenta pendiente (201 con indicador); no equivale a un fallo 500 del alta. No inventar cobertura de errores 500 arbitrarios. |

## Diseño autorizado tras la auditoría

Mantener demo principal breve y crear `demo:cu07:extras`. Configuración/reporter/helpers específicos de CU07; conservar sin cambios los de CU08–CU11. Modificar el launcher compartido sólo en ramas condicionales CU07. Build de producción y protecciones intactas. SQL limitado a fixture nuevo, testigos y vencimiento de un token real. Mailpit propio puede detenerse/reiniciarse para reproducir indisponibilidad SMTP. No interceptar HTTP. Conservar evidencias y hashes de archivos protegidos para comprobar alcance.

## Uso del modo presentación

Desde la raíz de este worktree:

```sh
npm --prefix frontend run demo:cu07
npm --prefix frontend run demo:cu07:extras
```

Sólo la presentación CU07 utiliza por defecto `slowMo=900 ms`, viewport 1440×1000, ventana solicitada 1500×1100 y pausas adicionales de 2500 ms. Todas las acciones verifican resultados/estados reales antes de la pausa. Se registran pasos en vivo, HTML, `cu07-steps.jsonl`, capturas enmascaradas y testigos SQL `cu07-checks/`. La demo principal muestra el buzón Mailpit real en otra ventana y vuelve automáticamente al registro.

Para ajustar el ritmo antes de presentar:

```sh
CU07_DEMO_SLOW_MS=1100 CU07_DEMO_PAUSE_MS=3000 npm --prefix frontend run demo:cu07
```

Las variables sólo afectan CU07. El parámetro `--slow-ms` tiene precedencia sobre `CU07_DEMO_SLOW_MS`. No se cambia la configuración de suites normales, CI o mediciones de rendimiento.

Cada complemento también puede ejecutarse por separado, con su propio proyecto Compose y base nueva:

```sh
npm --prefix frontend run demo:cu07:extras -- --scenario validation
npm --prefix frontend run demo:cu07:extras -- --scenario existing
npm --prefix frontend run demo:cu07:extras -- --scenario verification
npm --prefix frontend run demo:cu07:extras -- --scenario smtp
npm --prefix frontend run demo:cu07:extras -- --scenario backend
```

Los nombres corresponden a validaciones A2–A4, identidad existente A1, verificación A5–A7, recuperación SMTP A6/A8 y fallo real del backend A8. Cada uno usa cookies de un contexto nuevo y correos sintéticos propios. El fixture inicial contiene sólo una identidad con VENDEDOR y perfil; no crea tiendas, productos ni pedidos ni automatiza otros CU.

El vencimiento de A7 se prepara modificando únicamente la fecha de expiración del token real de la cuenta propia aún no verificada. La confirmación siempre se hace por UI y la regla de seguridad permanece intacta. Las interrupciones SMTP/backend se limitan a servicios del proyecto aleatorio CU07, se restauran en `finally` y esperan disponibilidad real. Al reiniciar Mailpit se vuelve a consultar su puerto efímero; el cambio queda en `cu07-service-restorations.jsonl`.

A8 prueba un fallo HTTP real del gateway (502/503/504) al detener el backend propio. Puede ocurrir al pedir CSRF o al enviar el alta; se registra la ruta real. No se afirma que un POST alcanzara la aplicación si falló antes durante CSRF. Después se restaura el backend, se repite el alta por UI y se verifica una única cuenta. No se inyecta un error 500 de base de datos a mitad de transacción; ese fallo específico continúa sin demostración visual.

## Cobertura nueva observada en Chrome real

Ejecución principal de referencia: `artifacts/sustentacion/sust-cu07-e8cdcc4eaf36/` (**aprobada**). Complementos completos: `artifacts/sustentacion/sust-cu07-a434713d0246/` (**cinco escenarios aprobados**). Ambos usaron slowMo 900 ms, pausa 2500 ms, Chrome visible, producción Angular/Ionic, backend Spring Boot, MySQL y SMTP Mailpit reales. Los `database-witness.json` y `cleanup.json` terminaron aprobados.

Identificadores de evidencia:

- **M**: principal, capturas `main-01` a `main-07`, pasos 01–06 y testigos `main-pending`, `main-activated`, `main-mailpit`.
- **X1**: validaciones A2–A4, `extras-validation-*`, `validation-no-account.json`, `validation-no-posts.json`.
- **X2**: cuenta existente, `extras-existing-*`, `existing-duplicate-1/2.json`, `existing-enabled.json`, `existing-no-new-verification.json`.
- **X3**: verificación, `extras-verification-*`, testigos `verification-pending/invalid/replaced/expired/recovered/used.json`.
- **X4**: SMTP indisponible y recuperación, `extras-smtp-*`, testigos `smtp-pending/no-duplicates/recovered.json`.
- **X5**: backend indisponible y recuperación, `extras-backend-*`, testigos `backend-unavailable-real-http/no-account`, `backend-retry-single-account/verified.json`. El resultado real fue **504 en `/api/auth/csrf`**, antes del POST de alta; al restaurar, el alta respondió 201 y quedó una sola cuenta verificada.

Los testigos están bajo `cu07-checks/`; las capturas bajo `screens/`. Los pasos tienen estado y duración en `cu07-steps.jsonl`. No se conservan códigos del buzón, cookies ni hashes de contraseñas en esos testigos.

### RF-190 y RF-191 después de la mejora

| Criterio | Resultado de implementación / prueba / presentación | Evidencia nueva aprobada |
|---|---|---|
| RF190.1 Crear comprador nuevo | Implementado; backend, UI real y demo principal aprobados | M: POST201, perfil y consentimiento persistidos, rol exacto COMPRADOR tras login |
| RF190.2 Asociar rol a identidad existente | Implementado funcionalmente; reserva semántica A1 por 409 | X2: misma identidad y credenciales/perfil preservados, dos roles, un consentimiento |
| RF190.3 Avisar si ya tiene COMPRADOR | Implementado y presentado | X2: aviso visible, botón habilitar ausente, una sola cuenta y rol |
| RF191.1 Mecanismo válido habilita | Implementado operacionalmente y presentado | M/X3: no verificado antes, SMTP real, confirmación204, verificado después, login204 |
| RF191.2 Mantener verificación al añadir rol | Implementado y presentado/comprobado | X2: verified=1, tokens=0 y ningún mensaje nuevo |
| RF191.3 Rechazar incorrecto/vencido/usado | Implementado y presentado | X3: confirmaciones400; no activación con incorrecto/sustituido/vencido; usado rechazado sin desactivar cuenta legítima |

### Trazabilidad individual de los 17 pasos después de la mejora

La implementación, pruebas previas y visibilidad anterior de cada paso constan en la matriz previa. Esta tabla añade el resultado visual real nuevo.

| Paso | Resultado de la demo nueva | Evidencia |
|---|---|---|
| 1 | Aprobado, acceso al registro | M paso01 / `main-01-formulario.png` |
| 2 | Aprobado, misma acción selecciona COMPRADOR; no hay selector aparte | M paso01 |
| 3 | Aprobado, formulario visible y listo | M paso01 |
| 4 | Aprobado, cuatro campos llenados automáticamente y perfil persistido | M paso02 / `main-pending.json`: profileMatches=1 |
| 5 | Aprobado, contraseña y confirmación; secretos enmascarados | M paso02; X1 presenta errores |
| 6 | Aprobado, ambas aceptaciones y consentimiento persistido | M paso02 / `main-02-datos-y-consentimientos.png`; SQL consents=1 |
| 7 | Aprobado, clic de alta por UI y respuesta201 | M paso03 |
| 8 | Aprobado para datos válidos; errores visibles en complemento | M paso03 / X1 sin POST ni alta ante errores |
| 9 | Aprobado funcionalmente; diferencia formal A1: 409 con orientación | M correo nuevo / X2 correo existente y reintento, sin duplicado |
| 10 | Aprobado; límites presentados en complemento | M contraseña válida / X1 corta y UTF-8 mayor de72 bytes |
| 11 | Aprobado operacionalmente: `ACTIVA` + correo NULL, acceso rechazado | M `main-pending.json`; X3 login403 EMAIL_NOT_VERIFIED |
| 12 | Aprobado con SMTP real | M paso04 / `main-mailpit.json` |
| 13 | Aprobado, mensaje exacto visible | M paso03 / `main-03-pendiente.png` |
| 14 | Aprobado, buzón y mensaje reales en otra ventana visible | M paso04 / `main-04-mailpit-bandeja.png`, `main-05-mailpit-mensaje.png` |
| 15 | Aprobado, mecanismo recibido confirmado por UI | M paso05 / confirmación204; X3 rechazos400 |
| 16 | Aprobado operacionalmente por correo verificado y token consumido | M `main-activated.json`: verified=1 y consumedTokens=1 |
| 17 | Aprobado, retorno al login y acceso nuevo | M pasos05/06 / `main-06-activado-login.png`, `main-07-comprador-autenticado.png` |

### A1–A8 después de la mejora

| Alternativa | Presentación nueva y resultado | Cobertura pendiente / diferencia |
|---|---|---|
| A1 | X2 aprobado: error orientativo409, identidad validada, rol añadido, aviso si ya comprador, sin nueva verificación | **Parcial frente al texto formal**: el alta anónima existente todavía se presenta como alerta/409; no se cambia negocio. La asociación funcional sí está comprobada. |
| A2 | X1 aprobado: mínimo12, máximo72 bytes, sin petición ni cuenta | No se enumeran todas las cadenas posibles; backend B3 y prueba Unicode también aprobados. |
| A3 | X1 aprobado: error de confirmación y datos conservados | Ninguna brecha identificada en el recorrido implementado. |
| A4 | X1 aprobado: ninguna aceptación, sólo términos y sólo privacidad | Ninguna brecha identificada en el recorrido implementado. |
| A5 | X3 aprobado: cuenta pendiente y login403 visible | Estado técnico ACTIVA+correo no verificado; no existe enum PENDIENTE. |
| A6 | X3/X4 aprobados: reenvíos reales, mecanismo nuevo y recuperación | No se usa SMTP productivo, únicamente Mailpit aislado. |
| A7 | X3 aprobado: incorrecto, sustituido, vencido y utilizado rechazados; recuperación válida | Vencimiento preparado por SQL propio; no se espera30min ni se altera el reloj o la regla de verificación. |
| A8 | X2/X4/X5 aprobados: reintento existente409, SMTP caído201-pendiente, gateway504 y recuperación sin duplicados | No se ha provocado un error de BD a mitad de transacción; concurrencia/unicidad sí tienen prueba backend B5 aprobada. |

## Pruebas relacionadas y límites de la evidencia

- `BuyerRegistrationIntegrationTests`: **23 aprobadas**, cero fallos/errores/omisiones; `BuyerProfileMigrationTests`: **1 aprobada**. MySQL Testcontainers real y correo Mockito: validación backend, no evidencia visual SMTP.
- `frontend/e2e/registro-comprador.spec.ts`: **3 aprobadas en3,0s**, con HTTP simulado. Son regresión UI normal, no prueba de operaciones reales ni de presentación.
- TypeScript estricto y sintaxis Python comprobados. No se ejecutan suites generales, otros CU, ZAP, k6 ni workflows.
- Los tests Java se ejecutaron en una copia separada bajo `artifacts/cu07-audit/audit-6ddd44e523c8/regression-source`, para no sobrescribir reportes existentes. Los XML nuevos saneados y el resumen se conservan en ese directorio de auditoría.
- Primer intento de extras `sust-cu07-260caa8a8b80`: tres escenarios aprobados y recuperación SMTP fallida por consultar el puerto efímero anterior. Fue un defecto de preparación del runner, corregido releyendo el puerto. Se conserva el intento y su limpieza aprobada.

Los requisitos se consideran auditados, no declarados universalmente satisfechos: permanecen explícitas la diferencia de A1, la representación operacional del estado pendiente y la ausencia de un fallo inyectado a mitad de transacción.

## Observaciones adicionales de la auditoría

En el alta anónima, el backend comprueba consentimiento, confirmación y política de contraseña **antes** de consultar si el correo existe. El orden formal de los pasos9/10 es inverso. El acceso directo «Ya tengo cuenta» permite autenticarse sin diligenciar nuevamente los datos de registro; no hay una asociación automática de rol basada sólo en escribir un correo. Esta diferencia queda incluida en la calificación parcial de A1; no se cambió el comportamiento ni se debilitó la protección contra enumeración de cuentas.

La repetición técnica SMTP sin pausas (`sust-cu07-2ebfe6166a2e`) detectó un503 real de entrega aun con HTTP Mailpit disponible. Se conservó ese intento fallido. La automatización de recuperación ahora solicita otro envío por UI sólo tras el error explícito503, con espera condicionada y límite45s; exige202 y correo real para aprobar. La repetición corregida `sust-cu07-0b37de4ff069` aprobó con estados `[503,503,503,202]`, sin cuenta ni rol duplicados. No se cambió el backend ni se sustituyó su respuesta.

## Duración y ritmo medidos

| Ejecución de presentación | Tiempo de escenarios | Proceso navegador | Comando completo, con build/arranque/limpieza | slowMo / pausa |
|---|---:|---:|---:|---|
| Principal, primera ejecución | 44,154s | 46,444s | 126,304s | 900 / 2500ms |
| Principal, repetición | 43,063s | Ver `cu07-presentation.json` | 88,313s | 900 / 2500ms |
| Extras completos: cinco escenarios | 249,948s (4min10s) | 252,418s | 310,659s | 900 / 2500ms |

Desglose de extras a ritmo de presentación: validaciones68,430s; cuenta existente32,028s; verificación61,570s; SMTP44,447s; backend43,473s. Para la sustentación conviene mostrar primero la principal y elegir sólo los complementos solicitados. La construcción inicial y el arranque Docker agregan tiempo fuera del recorrido visual.

Las repeticiones de independencia usan Chrome visible a100ms/pausa0; son verificaciones técnicas y no se presentan como ensayos del ritmo de exposición. Sus tiempos y reportes finales, incluidos el complemento SMTP repetido con ritmo900/2500, están en el índice de auditoría.

## Archivos de esta entrega

Modificados (3):

- `scripts/demos/run.py`: únicamente opciones/ramas CU07, selección de complemento, tiempos y log en vivo; comportamiento restante conservado.
- `frontend/package.json`: sólo se añade el script `demo:cu07:extras`; dependencias y versiones intactas.
- `frontend/e2e-sustentacion/cu07.spec.ts`: principal con pasos, Mailpit visible y testigos SQL.

Creados (7):

- `scripts/demos/cu07-seed.sql`
- `scripts/demos/cu07-fixture.py`
- `frontend/playwright.cu07-presentation.config.ts`
- `frontend/e2e-sustentacion/cu07-helpers.ts`
- `frontend/e2e-sustentacion/cu07-reporter.ts`
- `frontend/e2e-sustentacion/cu07-extras.spec.ts`
- `docs/ejecucion/auditoria-demo-cu07.md`

La comparación de1336 archivos protegidos confirma que el único archivo previo de esa lista que cambia es el launcher autorizado. Código de negocio, demás CU, RNF, workflows, Compose, helpers/reporter compartidos y lockfile permanecen intactos. El JSON de package, retirando el nuevo script CU07, coincide exactamente con el anterior. No se hicieron operaciones Git.

Índice final y resultados: `artifacts/cu07-audit/audit-6ddd44e523c8/validation.md`. Los intentos se conservan bajo sus directorios únicos de `artifacts/sustentacion/`; no se borraron evidencias anteriores.
