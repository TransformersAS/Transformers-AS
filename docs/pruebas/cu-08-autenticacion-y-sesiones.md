# CU-08 — Pruebas

## 1. Resumen

CU-08 tiene pruebas de autenticación, correo verificado, recuperación/cambio de contraseña, autorización por rol activo, sesiones JDBC normales/persistentes, CSRF, revocación y aislamiento entre cuentas. La cobertura combina pruebas backend, pruebas de interfaz con API interceptada y **un E2E real mínimo de login/logout**.

Este documento describe los archivos actuales. La existencia de una prueba no acredita una ejecución verde del commit actual: los resultados disponibles y sus límites se distinguen en la sección 8. No se reprodujeron secretos ni credenciales de las fixtures.

## 2. Pruebas backend

Las rutas de la tabla son relativas a `backend/demo/src/test/java/com/transformersas/marketplace/auth/`; cada enlace apunta al archivo completo. Las integraciones HTTP usan Spring Security y persistencia real con MySQL/Testcontainers. Las de correo utilizan GreenMail local, no un proveedor SMTP externo.

| Archivo / ruta relativa | Tipo | Qué valida |
| --- | --- | --- |
| [SessionAuthenticationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/SessionAuthenticationTests.java) | Integración y seguridad | Login, credenciales genéricas, rotación de sesión, CSRF, roles activos, logout, listado/revocación, cambio y recuperación de contraseña; consumo concurrente del token. |
| [PersistentSessionIntegrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/PersistentSessionIntegrationTests.java) | Integración JDBC | rememberMe, cookie normal/persistente, duración, restauración y expiración; logout, revocación y cierre de las demás sesiones conservando la actual y otras cuentas. |
| [CurrentAccountSessionIntegrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/CurrentAccountSessionIntegrationTests.java) | Integración y autorización | Cambios de cuenta/roles después del login: cuenta inactiva o eliminada, rol retirado, /me actualizado y ausencia de selección automática; ambas modalidades de sesión. |
| [EmailVerificationIntegrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/EmailVerificationIntegrationTests.java) | Integración MySQL + SMTP local | Rechazo de login sin correo verificado, envío/reenvío, verificación, hash, expiración, consumo único/concurrente, CSRF y fallo SMTP. |
| [PasswordRecoverySmtpIntegrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/PasswordRecoverySmtpIntegrationTests.java) | Integración MySQL + SMTP local | Mensaje recibido en GreenMail, token utilizable sin texto plano en persistencia, respuesta genérica, fallo SMTP, contraseña nueva y revocación; separación de verificación y recuperación. |
| [BuyerIsolationIntegrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/BuyerIsolationIntegrationTests.java) | Integración y aislamiento | Rol COMPRADOR activo; carrito, direcciones, checkout, reservas y pagos propios; rechazo de identificadores ajenos, suplantaciones y datos antiguos sin dueño. |
| [BuyerOwnershipMigrationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/BuyerOwnershipMigrationTests.java) | Integración de migración | Migración de recursos a propiedad por cuenta: conserva filas antiguas sin asignarles un propietario e impone integridad. |
| [RemainingAccessBlockersTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/RemainingAccessBlockersTests.java) | Integración y autorización | B1: creación rechazada para COMPRADOR/SOPORTE/ADMIN o sin rol activo, vendedor usa su tienda y no una ajena. B2: interacciones y recomendaciones usan el principal aunque el cliente envíe otro userId; rechazo anónimo. |
| [SessionSellerActorProviderTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/SessionSellerActorProviderTests.java) | Unitaria con colaboradores simulados | Identidad del vendedor, exigencia de rol activo y resolución de tienda; rechaza cabeceras inválidas y propaga rechazo de propiedad. |
| [application/usecase/ManageAccountSessionsTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/application/usecase/ManageAccountSessionsTests.java) | Unitaria con repositorio simulado | Filtrado de sesiones propias autenticadas/no expiradas, identificador de gestión y revocación sin borrar sesiones ajenas. |
| [application/usecase/RecoverAccountPasswordValidationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/application/usecase/RecoverAccountPasswordValidationTests.java) | Unitaria | Correo ausente/excesivo y tokens mal formados se descartan antes de consultas, notificaciones o cambios de sesiones. |
| [infrastructure/notification/RecoveryNotificationConfigurationTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/infrastructure/notification/RecoveryNotificationConfigurationTests.java) | Configuración y adaptador | Adaptador SMTP predeterminado o reemplazable, remitente independiente, fallo por configuración ausente y ausencia de secretos en logs. |
| [infrastructure/notification/SmtpEmailVerificationNotifierTests.java](../../backend/demo/src/test/java/com/transformersas/marketplace/auth/infrastructure/notification/SmtpEmailVerificationNotifierTests.java) | Unitaria de adaptador | Ausencia de transporte SMTP o remitente produce error, sin fingir entrega. |

La infraestructura compartida está en [backend/demo/src/test/java/com/transformersas/marketplace/support/AbstractIntegrationTest.java](../../backend/demo/src/test/java/com/transformersas/marketplace/support/AbstractIntegrationTest.java). No todas las clases usan el mismo contexto: `SessionAuthenticationTests` dispone de su propia configuración de integración. Las pruebas unitarias con colaboradores simulados no sustituyen las pruebas JDBC.

## 3. Playwright de interfaz

Las pruebas siguientes ejercitan componentes Angular/Ionic, `AuthService` y el interceptor de sesión reales. **Todas interceptan `**/api/**` mediante `page.route()` y proporcionan respuestas controladas**: validan UI y contrato HTTP, pero no prueban por sí mismas Spring Boot, MySQL ni entrega de correo.

| Archivo | Casos por proyecto | Flujo y frontend validado |
| --- | ---: | --- |
| [email-verification.spec.ts](../../frontend/e2e/email-verification.spec.ts) | 1 | Login bloqueado por correo pendiente, opción de reenvío, error de entrega y confirmación desde el panel de acceso. |
| [persistent-session.spec.ts](../../frontend/e2e/persistent-session.spec.ts) | 2 | Checkbox rememberMe, parámetro enviado, restauración mediante /me y limpieza de la opción tras logout. |
| [logout.spec.ts](../../frontend/e2e/logout.spec.ts) | 12 | Éxito y errores de backend/red/401/CSRF/obtención de CSRF; conserva identidad ante fallo y limpia solo tras éxito, en ambas modalidades. |
| [revoke-other-sessions.spec.ts](../../frontend/e2e/revoke-other-sessions.spec.ts) | 5 | Acción de cierre de otras sesiones, lista actualizada, errores de backend/red/CSRF y fallo al recargar la lista. |
| [remaining-access-blockers.spec.ts](../../frontend/e2e/remaining-access-blockers.spec.ts) | 2 | Único rol con activeRole nulo: selección explícita. Cuenta A → logout → cuenta B: no muestra el carrito anterior aunque falle la nueva carga. |

Son **22 casos de CU-08**. La suite normal Chrome suma **23** al incluir [home.spec.ts](../../frontend/e2e/home.spec.ts), una prueba general de portada que no se cuenta como cobertura específica de CU-08. Los números se obtienen de las declaraciones y bucles de parametrización actuales.

La configuración [frontend/playwright.config.ts](../../frontend/playwright.config.ts) ofrece Chrome, Edge y mobile; CI selecciona Chrome. Desactiva live reload/HMR del servidor de desarrollo para que una recarga ajena a la prueba no destruya el panel. Esa configuración no cambia la autenticación de producción.

## 4. E2E real CU-08

Archivo: [frontend/e2e-real/account-session.spec.ts](../../frontend/e2e-real/account-session.spec.ts). Contiene un caso:

1. Abre el frontend e introduce las credenciales de la fixture desde la UI.
2. Comprueba el panel autenticado y la identidad visible.
3. Consulta `/api/auth/me` con las cookies del navegador: exige **200**, correo/roles esperados y cookie `SESSION` HttpOnly.
4. Cierra sesión desde la UI y exige **204** en `POST /api/auth/logout`.
5. Comprueba cierre del panel, estado visitante y `/api/auth/me` → **401**.
6. Un cliente independiente reutiliza la cookie anterior: exige **401**. Esto verifica invalidación del lado servidor, no solo eliminación de la cookie del navegador.
7. Reabre el panel y comprueba que muestra el inicio de sesión.

**No usa `page.route()`, mocks ni respuestas simuladas.** Usa Chrome real, frontend compilado servido por Nginx, backend Spring Boot real y MySQL real **8.4.11**. Las consultas `page.request`/`request` son HTTP reales. El test bloquea service workers; no necesita SMTP porque la cuenta ya está verificada.

| Infraestructura | Función |
| --- | --- |
| [compose.e2e.yaml](../../compose.e2e.yaml) | Levanta MySQL, backend y frontend aislados; construye con los Dockerfiles del repositorio y espera healthchecks. Solo publica el frontend en loopback. |
| [scripts/cu08-real-e2e.sh](../../scripts/cu08-real-e2e.sh) | Crea un proyecto Compose desechable, construye/espera servicios, ejecuta el seed y Playwright; registra limpieza con `trap EXIT`. |
| [scripts/cu08-real-seed.sql](../../scripts/cu08-real-seed.sql) | Inserta la cuenta verificada y su rol después de Flyway. |
| [frontend/playwright.real.config.ts](../../frontend/playwright.real.config.ts) | Suite independiente en `e2e-real`, Chrome, un worker, cero reintentos; informe HTML y traza en fallos. |

Los archivos [frontend/Dockerfile](../../frontend/Dockerfile), [frontend/nginx.conf](../../frontend/nginx.conf) y [backend/demo/Dockerfile](../../backend/demo/Dockerfile) materializan el recorrido navegador → Nginx/frontend → Spring → MySQL. El proxy `/api` de Nginx dirige las llamadas al backend del mismo entorno.

## 5. Datos de prueba

El script espera que backend y base estén preparados, con las migraciones Flyway aplicadas, antes de ejecutar el SQL dentro del contenedor MySQL. La fixture tiene estado `ACTIVA`, `email_verified_at` informado, rol `COMPRADOR` y contraseña almacenada como BCrypt. Las credenciales de prueba están en los archivos de fixture; no se reproducen aquí.

Cada ejecución utiliza un nombre de proyecto Compose basado en el proceso y una base desechable. El seed es una inserción para esa base nueva, **no un script idempotente para una base existente**. No utiliza la base de desarrollo ni carga el `.env` local: Compose recibe `--env-file /dev/null`.

Al salir, incluso tras un fallo normal del comando, el trap solicita `down --volumes --remove-orphans`; en fallo también imprime logs. La limpieza usa `|| true`: un fallo de Docker durante la eliminación no cambia el resultado original y debe revisarse en los logs. No configura ni contacta SMTP externo.

## 6. Ejecución local

Requisitos: Java 21 y Docker disponible para backend; Node/npm y Chrome de Playwright para navegador; Docker Compose para el E2E real. Los comandos siguientes se ejecutan desde la raíz, salvo los bloques que cambian de directorio.

Pruebas backend relacionadas, con el wrapper y clases existentes en [backend/demo/pom.xml](../../backend/demo/pom.xml):

```bash
cd backend/demo
./mvnw -Dtest=SessionAuthenticationTests,PersistentSessionIntegrationTests,CurrentAccountSessionIntegrationTests,EmailVerificationIntegrationTests,PasswordRecoverySmtpIntegrationTests,BuyerIsolationIntegrationTests,BuyerOwnershipMigrationTests,RemainingAccessBlockersTests,SessionSellerActorProviderTests,ManageAccountSessionsTests,RecoverAccountPasswordValidationTests,RecoveryNotificationConfigurationTests,SmtpEmailVerificationNotifierTests test
```

Preparación y Playwright normal, según [frontend/package.json](../../frontend/package.json) y su configuración:

```bash
cd frontend
npm ci
npx playwright install --with-deps chrome
npx playwright test --project=chrome
```

El servidor de desarrollo usa el puerto 4300 y permite reutilizar un servidor ya existente: debe corresponder al checkout que se quiere validar.

E2E real, desde la raíz y con las dependencias/Chrome anteriores instalados:

```bash
bash scripts/cu08-real-e2e.sh
```

El script levanta todo automáticamente. Usa por defecto el puerto 14300; puede cambiarse mediante la variable existente `CU08_E2E_PORT`. No se debe ejecutar el seed contra otra base. `CU08_REAL_BASE_URL` es la URL que el script proporciona a Playwright.

Build frontend:

```bash
cd frontend
npm run build
```

Estas instrucciones describen comandos disponibles; no implican que se hayan vuelto a ejecutar al redactar esta documentación.

## 7. CI / GitHub Actions

Workflow: [.github/workflows/backend-ci.yml](../../.github/workflows/backend-ci.yml). Se activa por push a ramas.

| Job | Ejecución y efecto del fallo |
| --- | --- |
| `validate` / Maven verification | Java 21, Docker y `./mvnw clean verify`. Un fallo hace fallar el job; conserva reportes Surefire/JaCoCo con `always()`. |
| `frontend-e2e` | Node 22, `npm ci`, instalación de Chrome, `npm run build`, Playwright normal Chrome y después `bash scripts/cu08-real-e2e.sh`. Un fallo impide los pasos normales posteriores y hace fallar el job; informes se suben con `always()`. |
| `build` | Fuera de main, depende de `validate` y construye imágenes backend/frontend sin publicarlas. |
| `publish` | En main, depende de `validate` y construye/publica imágenes backend/frontend en GHCR. |

Un job de pruebas fallido impide considerar verde el workflow. **`build` y `publish` dependen de Maven, pero no tienen `needs: frontend-e2e`**: Playwright no es actualmente una dependencia explícita que bloquee esos jobs. No se presupone una regla de protección de rama que no figure en este archivo.

Los artefactos de CI incluyen reportes backend y los directorios de informes Playwright normal/real, además de trazas/resultados del real, con retención de 14 días. El workflow muestra la automatización configurada, no acredita por sí solo una ejecución remota exitosa.

## 8. Resultados actuales

Al redactar se inspeccionaron los reportes locales disponibles; **no se ejecutaron nuevas pruebas**. Son evidencia histórica del workspace, no una certificación del HEAD ni resultados de GitHub Actions consultados en remoto.

| Evidencia disponible | Resultado comprobado |
| --- | --- |
| Reportes Surefire de `RemainingAccessBlockersTests` y `BuyerIsolationIntegrationTests` | 2 y 14 casos respectivamente, sin fallos, errores ni omitidos. |
| Reportes Surefire de `SessionAuthenticationTests`, `PersistentSessionIntegrationTests` y `CurrentAccountSessionIntegrationTests` | 37, 26 y 16 casos respectivamente, sin fallos, errores ni omitidos. |
| Reportes Surefire de `EmailVerificationIntegrationTests` y `PasswordRecoverySmtpIntegrationTests` | 9 y 5 casos respectivamente, sin fallos, errores ni omitidos. |
| Última ejecución local disponible de Playwright normal durante la validación del E2E real | **23 aprobados**. Incluye la portada general y los 22 casos de CU-08. |
| Última ejecución local disponible del script E2E real | **1/1 aprobado**; los logs posteriores muestran eliminación de sus contenedores y red. |

Los reportes backend se encuentran, cuando se han generado, en `backend/demo/target/surefire-reports/` con el nombre completo de cada clase. No se suman como si pertenecieran a una única ejecución. Los logs Playwright consultados en esta sesión fueron `/tmp/cu08-real-mocked-regression.log` y `/tmp/cu08-real-e2e-final.log`: son temporales locales y no archivos versionados. Para evidencia portable se deben conservar los artefactos del workflow correspondiente al commit de entrega.

No se ha comprobado aquí el estado del último pipeline remoto. El E2E real cubre login/logout normal, no todos los flujos de correo, roles o sesiones persistentes. Los casos anteriores se distribuyen entre las pruebas backend y de interfaz.

## 9. Matriz de cobertura CU-08

Los nombres de archivo remiten a las rutas enlazadas en las secciones 2–4.

| Funcionalidad | Tipo de prueba | Archivo | Qué comprueba |
| ------------- | -------------- | ------- | ------------- |
| Login y credenciales incorrectas | Integración de seguridad | `SessionAuthenticationTests.java` | Sesión autenticada o rechazo genérico; rotación del identificador. |
| Correo obligatorio y reenvío | Integración SMTP + UI simulada | `EmailVerificationIntegrationTests.java`, `email-verification.spec.ts` | Acceso bloqueado, envío, confirmación, expiración y error de correo. |
| Recuperación y cambio de contraseña | Integración | `PasswordRecoverySmtpIntegrationTests.java`, `SessionAuthenticationTests.java` | Hash, token de uso único, contraseña nueva y revocación correspondiente. |
| Roles y cuenta vigente | Integración | `CurrentAccountSessionIntegrationTests.java`, `SessionAuthenticationTests.java` | Retirada de permisos, desactivación, /me vigente y selección explícita. |
| Rol único sin selección | UI simulada | `remaining-access-blockers.spec.ts` | El usuario puede seleccionar su único rol sin volver a iniciar sesión. |
| Sesiones normales y rememberMe | Integración JDBC + UI simulada | `PersistentSessionIntegrationTests.java`, `persistent-session.spec.ts` | Cookie, duración real, restauración, expiración y parámetro de UI. |
| Listado y revocación individual | Integración y unitaria | `SessionAuthenticationTests.java`, `ManageAccountSessionsTests.java` | Solo sesiones propias; identifica y puede revocar la actual. |
| Cerrar las demás sesiones | Integración JDBC + UI simulada | `PersistentSessionIntegrationTests.java`, `revoke-other-sessions.spec.ts` | Conserva actual/otras cuentas y revoca las demás; éxito y error en UI. |
| Logout y errores | Integración + UI simulada | `SessionAuthenticationTests.java`, `logout.spec.ts` | Invalidación backend; UI no finge éxito ante errores. |
| CSRF | Integración y UI simulada | `SessionAuthenticationTests.java`, `PersistentSessionIntegrationTests.java`, `logout.spec.ts` | Rechazo sin token o con token incorrecto y manejo de renovación en UI. |
| Aislamiento comprador | Integración y migración | `BuyerIsolationIntegrationTests.java`, `BuyerOwnershipMigrationTests.java` | Carrito, direcciones, reservas y pagos no usan recursos ajenos. |
| Creación de productos e identidad de actividad | Integración | `RemainingAccessBlockersTests.java` | Rol vendedor/tienda propia y principal en interacciones/recomendaciones. |
| Limpieza del carrito entre cuentas | UI simulada | `remaining-access-blockers.spec.ts` | No muestra el carrito de A al entrar B, incluso con fallo de carga. |
| Recorrido real de sesión | E2E real | `account-session.spec.ts` | Login UI, /me 200, logout UI, /me 401 y cookie anterior rechazada. |

## 10. Evidencia para sustentación

Guion de menos de un minuto, con ejecución/artefactos preparados:

1. Mostrar GitHub Actions verde **del commit de entrega**, si está disponible.
2. Mostrar el resultado Playwright normal y distinguir sus mocks de API.
3. Mostrar el resultado **E2E real 1/1**.
4. Abrir `account-session.spec.ts`: señalar login/logout y rechazo de la cookie anterior.
5. Abrir `compose.e2e.yaml` y `scripts/cu08-real-e2e.sh`: señalar servicios, seed y limpieza.
6. Explicar: **Chrome → frontend compilado/Nginx → Spring Boot → MySQL real**. No mostrar contraseñas, contenido de cookies ni tokens.
