# Informe técnico: almacenamiento y gestión de sesiones del Marketplace

**Fecha de verificación:** 26 de octubre de 2026.  
**Alcance:** uso de almacenamiento del navegador, autenticación y sesiones en el código actual. La actualización del SAD queda fuera de este informe.

## 1. Objetivo

Identificar si el Marketplace utiliza `localStorage` para almacenar credenciales, tokens, información personal o datos de sesión; evaluar el mecanismo existente y verificar el funcionamiento de la autenticación mediante pruebas de interfaz e integración.

## 2. Diagnóstico del uso de localStorage

La revisión del código fuente del frontend no encontró referencias a `localStorage` ni a `sessionStorage`. Por tanto, no se identificaron claves, valores ni operaciones de lectura o escritura que debieran migrarse.

La búsqueda también incluyó referencias directas a IndexedDB, cookies desde JavaScript y Cache API. Se complementó con la lectura del servicio de autenticación, el interceptor HTTP, la política de cookies, la configuración de seguridad y la configuración del service worker.

Comando de inspección desde la raíz del repositorio:

```powershell
rg -n -i 'localStorage|sessionStorage|indexedDB|document\.cookie|caches\.' frontend/src
```

**Resultado:** sin coincidencias. Este resultado corresponde al código fuente propio inspeccionado; no representa una auditoría exhaustiva de dependencias de terceros ni de versiones desplegadas anteriormente.

## 3. Qué se utiliza en lugar de localStorage

El Marketplace utiliza **sesiones en el servidor mediante Spring Security y Spring Session JDBC**, identificadas en el navegador por una **cookie `SESSION` con `HttpOnly`**.

La cookie conserva un identificador que permite localizar la sesión. El contexto autenticado se mantiene en la base de datos del servidor, en las tablas de Spring Session. El frontend no necesita guardar un token de autenticación en Web Storage.

| Información | Dónde se mantiene | Para qué se utiliza |
| --- | --- | --- |
| Identificador de sesión | Cookie `SESSION`, administrada por el navegador | Asociar las peticiones con una sesión del servidor |
| Contexto de autenticación y vigencia | Base de datos, tablas `SPRING_SESSION` y `SPRING_SESSION_ATTRIBUTES` | Mantener la sesión, aplicar expiración y permitir revocación |
| ID de cuenta, correo y roles visibles | Memoria de `AuthService`, mediante un signal | Presentar la identidad y opciones en la interfaz |
| Token CSRF | Memoria de `AuthService` | Proteger las peticiones que modifican datos |
| Contraseña introducida | Temporalmente en el formulario y en el cuerpo del POST de login | Autenticar al usuario; no se guarda en Web Storage |
| Preferencia de mantener sesión | Parámetro `rememberMe` enviado al iniciar sesión | Solicitar al servidor su política de persistencia |

**El token CSRF no es un token de autenticación.** El navegador envía la cookie automáticamente y el interceptor adjunta CSRF a las operaciones de escritura de la API propia. Los roles visibles en memoria sirven a la interfaz; la autorización la decide el backend.

El service worker está configurado para almacenar el shell y los recursos estáticos. No tiene `dataGroups` configurados para almacenar respuestas de la API.

## 4. Funcionamiento de la autenticación

### Inicio de sesión

1. El frontend solicita un token CSRF mediante `GET /api/auth/csrf`.
2. Envía correo, contraseña y preferencia de persistencia a `POST /api/auth/login`, incluyendo la protección CSRF.
3. El backend valida las credenciales y las condiciones de acceso de la cuenta.
4. Se establece la sesión autenticada, con rotación del identificador, y se administra la cookie `SESSION`.
5. El frontend renueva el token CSRF y consulta `GET /api/auth/me` para mostrar la cuenta.

### Recarga y persistencia

Al recargar la página se pierde el estado de los servicios en memoria. `AuthService.restaurar()` consulta `/api/auth/me`; el navegador envía la cookie y el backend comprueba la sesión. De esta manera se recupera la identidad sin consultar `localStorage`.

| Modalidad | Inactividad máxima en el servidor | Cookie |
| --- | --- | --- |
| Login normal | 30 minutos | Cookie de sesión, sin Max-Age persistente |
| «Mantener la sesión iniciada» | 7 días | Max-Age de 604800 segundos y Expires |

La expiración del servidor se calcula por inactividad. La cookie persistente tiene su propio vencimiento; las consultas ordinarias no renuevan automáticamente su Max-Age. El acceso requiere tanto una cookie disponible como una sesión vigente.

### Cierre de sesión y restricciones

El frontend envía `POST /api/auth/logout` con CSRF. El servidor invalida la sesión y elimina su cookie. Solo tras confirmar el éxito, el frontend limpia la identidad y el token CSRF de memoria. Si ocurre un error, la interfaz no presenta un cierre de sesión como exitoso.

Las pruebas verifican que la cookie anterior no permite autenticarse después del logout. El backend también comprueba los roles y el estado vigente de la cuenta, y permite revocar sesiones. Ante un 401 de una petición protegida, el frontend limpia la identidad local, salvo la excepción de negocio prevista para una cuenta válida sin tienda.

## 5. Justificación de seguridad

Guardar identificadores de sesión en `localStorage` los expone a JavaScript del mismo origen. Una vulnerabilidad XSS podría permitir leerlos. `sessionStorage` también es accesible desde JavaScript, aunque tenga una duración distinta. Por ello, cambiar de uno a otro no resolvería por sí solo esa exposición. Esta evaluación coincide con la [guía de seguridad HTML5 de OWASP](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html).

La solución existente incorpora estos controles:

- **HttpOnly:** impide la lectura directa de la cookie mediante JavaScript.
- **SameSite=Lax:** limita determinados envíos de cookies desde otros sitios.
- **CSRF:** protege las operaciones de escritura; SameSite no reemplaza este control.
- **Expiración y revocación en el servidor:** una cookie antigua no mantiene el acceso si su sesión fue invalidada.
- **Autorización en el backend:** los datos visibles del navegador no conceden permisos.

`Secure` se aplica cuando el backend reconoce la petición como HTTPS. La configuración del proxy y TLS debe verificarse en producción; las pruebas de una petición marcada como segura no certifican el despliegue real. Asimismo, HttpOnly no evita que un XSS realice peticiones en nombre del usuario. Véase la [guía de gestión de sesiones de OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

## 6. Componentes revisados y modificaciones

| Componente | Responsabilidad |
| --- | --- |
| [AuthService](../../frontend/src/app/core/services/auth.service.ts) | Login, recuperación de identidad, logout y estado en memoria |
| [sessionInterceptor](../../frontend/src/app/core/interceptors/session.interceptor.ts) | Credenciales, CSRF y tratamiento de errores de sesión |
| [LoginSessionPolicy](../../backend/demo/src/main/java/com/transformersas/marketplace/auth/infrastructure/security/LoginSessionPolicy.java) | Atributos de cookie y política de persistencia |
| [SecurityConfiguration](../../backend/demo/src/main/java/com/transformersas/marketplace/shared/SecurityConfiguration.java) | Autenticación, CSRF, autorización y logout |
| [CurrentAccountSessionFilter](../../backend/demo/src/main/java/com/transformersas/marketplace/auth/infrastructure/security/CurrentAccountSessionFilter.java) | Revalidación de cuenta y permisos vigentes |

**No fue necesario reemplazar almacenamiento ni modificar la implementación:** el mecanismo de sesiones ya estaba presente y no se encontraron usos de Web Storage que incumplieran el objetivo revisado. Este informe registra la revisión y las pruebas; no atribuye al trabajo una migración que no se realizó.

## 7. Pruebas ejecutadas y resultados

Se ejecutaron las siguientes suites el 26 de octubre de 2026:

| Grupo | Suite | Casos aprobados |
| --- | --- | ---: |
| Interfaz | `persistent-session.spec.ts`, `logout.spec.ts`, `revoke-other-sessions.spec.ts`, `remaining-access-blockers.spec.ts` | 63 |
| Integración | `SessionAuthenticationTests` | 37 |
| Integración | `PersistentSessionIntegrationTests` | 26 |
| Integración | `CurrentAccountSessionIntegrationTests` | 16 |
| **Total** | | **142** |

**Resultado: 142 aprobadas, sin fallos.** Las 79 pruebas de integración terminaron sin errores ni casos omitidos y Maven finalizó con `BUILD SUCCESS`.

Las pruebas de interfaz se ejecutaron en Chrome, Edge y una vista móvil de Chrome. Utilizan una API simulada: comprueban los componentes, el servicio, el interceptor y los flujos de la interfaz. La vista móvil no equivale a una prueba en un teléfono físico.

Las pruebas de integración ejecutaron Spring Boot con MySQL 8.4.11 mediante Testcontainers. Verificaron autenticación, cookies, rotación del identificador, ausencia de contraseña y hash en el contexto de sesión probado, expiración, restauración, revocación, CSRF, permisos y rechazo de cookies antiguas. No se ejecutó en esta verificación la suite adicional de navegador con backend real `e2e-real/account-session.spec.ts`.

Al inicio, Maven intentó compilar con Java 17 y falló porque el proyecto exige Java 21. Se repitió la ejecución utilizando el Java 21 instalado, sin modificar el proyecto, y las pruebas finalizaron correctamente.

### Comandos de reproducción

Desde `frontend`, con dependencias y navegadores instalados:

```powershell
node node_modules/@playwright/test/cli.js test e2e/persistent-session.spec.ts e2e/logout.spec.ts e2e/revoke-other-sessions.spec.ts e2e/remaining-access-blockers.spec.ts --workers=2 --reporter=list
```

Desde `backend/demo`, con Java 21, Maven y Docker disponibles:

```powershell
mvn.cmd -Dtest=SessionAuthenticationTests,PersistentSessionIntegrationTests,CurrentAccountSessionIntegrationTests test
```

En esta máquina también se indicó explícitamente el repositorio Maven existente y se seleccionó Java 21 para el proceso de pruebas. Las rutas locales de esas herramientas no forman parte de la configuración del sistema.

### Evidencia generada

- Playwright: salida de ejecución `63 passed (50.6s)` y estado `passed` en `frontend/test-results/.last-run.json`.
- Maven: salida `Tests run: 79, Failures: 0, Errors: 0, Skipped: 0` y `BUILD SUCCESS`.
- Reportes Surefire de las tres suites en `backend/demo/target/surefire-reports/`, actualizados entre las 17:50 y 17:51, hora de Bogotá, el día de la ejecución.

Los archivos de resultados son artefactos locales generados por las herramientas y pueden reemplazarse en ejecuciones posteriores. El archivo `.last-run.json` indica el estado de Playwright; el número de 63 pruebas se obtuvo de la salida de ejecución.

## 8. Conclusión y alcance de la aceptación

El frontend revisado no utiliza `localStorage` ni `sessionStorage` para guardar información. La autenticación utiliza una cookie HttpOnly como identificador y mantiene la sesión en el servidor mediante Spring Session JDBC. Por ello, no fue necesaria una sustitución de almacenamiento.

Las 142 pruebas ejecutadas respaldan el funcionamiento de los flujos y controles incluidos en las suites seleccionadas. Estos resultados no equivalen a una auditoría completa de seguridad ni a una certificación de un despliegue de producción. La actualización del SAD, sus diagramas y las decisiones arquitectónicas se deja para una actividad posterior, conforme al alcance solicitado.
