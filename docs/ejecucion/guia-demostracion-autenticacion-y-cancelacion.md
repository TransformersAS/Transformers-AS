# Guía de ejecución manual — CU-08 y CU-11

## 1. Objetivo de la guía

Guía para demostrar directamente el software durante la sustentación, con preparación fuera del tiempo de exposición. CU-08 demuestra login, rol activo, dos sesiones independientes, revocación selectiva y logout. CU-11 demuestra compra, consulta del pedido propio, cancelación definitiva con motivo, resultado del reembolso y persistencia tras recargar.

Cada paso distingue la acción de la evidencia visible. Los estados técnicos `CONFIRMED`, `IN_PREPARATION` y `CANCELLED` aparecen en pantalla como **Confirmado**, **En preparación** y **Cancelado**. El código técnico se puede comprobar en la respuesta HTTP de la pestaña Network/Red del navegador.

También existen pruebas backend de permisos, propiedad, concurrencia y errores; pruebas frontend con colaboradores simulados; Playwright con API interceptada; y un E2E real local de CU-08. El E2E real de CU-11 pertenece al repositorio separado Transformers-Integration-Tests (§8).

**Límite encontrado al inspeccionar el código:** el perfil `local` crea la cuenta demo, pero no marca su correo como verificado. Una instalación nueva puede estar completamente saludable y aun así rechazar su login. La preparación de §4 es obligatoria. Esta guía no supone una verificación automática, no añade SQL de datos y no cambia el sistema para eludir ese requisito.

La documentación se contrastó con fuentes del repositorio, no con una nueva ejecución de todas las pruebas ni con un pipeline remoto. El README principal conserva descripciones antiguas de autenticación pendiente: para estos CU prevalecen `SecurityConfiguration`, los controladores y las plantillas actuales.

## 2. Prerrequisitos

| Necesidad | Requisito exacto para la demo manual |
| --- | --- |
| Contenedores | Docker activo y Docker Compose v2 con `up --wait`. En macOS, Docker Desktop iniciado. |
| Herramientas host | Terminal y `curl`; acceso inicial a registros de imágenes y dependencias para construir. |
| Java / Node | No se necesitan instalados en el host para levantar los tres servicios con Compose: los Dockerfiles construyen las aplicaciones. Java 21 y Node 22 solo se necesitan para las pruebas opcionales indicadas en §§6 y 8. |
| Configuración | `.env` en la raíz, con `DB_PASSWORD` y `MYSQL_ROOT_PASSWORD` definidos; no hay un `.env` adicional del frontend para esta ruta. |
| Puertos host | Frontend 4300, backend 8080, MySQL 3307; publicados en `127.0.0.1`. Dentro de Docker: frontend 80, backend 8080, MySQL 3306. |
| Navegador | Chrome de escritorio recomendado; una ventana normal A y una ventana de incógnito B. No son dos pestañas normales. |
| Cuenta | Cuenta ACTIVA, correo verificado y roles COMPRADOR/VENDEDOR. Si no existe una, preparar SMTP y un buzón accesible mediante §4. |
| Producto | `Camiseta demo local` activa y con existencias; la crea el perfil `local` si no existe. |

SMTP no es necesario para login/sesiones/compras de una cuenta **ya verificada**. Sí es necesario para registrar/verificar una nueva cuenta por el flujo existente y para demostrar recuperación de contraseña. No se necesita instalar MySQL, Maven, Nginx ni Swarm en el host. `stack.yml` y `deploy.sh --local` son la alternativa Swarm, con secretos y otros puertos; no se mezclan con esta ruta Compose.

Todos los comandos de arranque/diagnóstico se ejecutan desde la raíz. Los comandos de pruebas indican su carpeta. Mantener siempre `http://127.0.0.1:4300` en ambas ventanas: no alternar con `localhost`, porque son hosts distintos para las cookies.

## 3. Levantar el sistema desde cero

### 3.1 Ubicarse en el repositorio

En el equipo de este workspace:

```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
```

En otro equipo, sustituir únicamente esa ruta por la carpeta clonada `Transformers-AS`.

### 3.2 Preparar configuración

Desde la raíz, copiar solo si no existe `.env`:

```bash
test -f .env || cp .env.example .env
```

Abrir `.env` en el editor. Sustituir los dos valores `REPLACE_WITH_...` por contraseñas locales distintas elegidas por quien prepara el entorno. No son credenciales de acceso al Marketplace. Conservar los valores de `.env.example` para base/usuario y estos puertos:

```dotenv
DB_HOST=localhost
DB_PORT=3307
MYSQL_HOST_PORT=3307
BACKEND_HOST_PORT=8080
```

Si ya existe `FRONTEND_PORT`, comprobar que vale `4300`; si no existe, Compose usa 4300. Activar la opción documentada:

```dotenv
SPRING_PROFILES_ACTIVE=local
```

No activar avance logístico automático para esta demostración. Si `.env` ya contiene `LOGISTICS_SIMULATED_SCRIPT`, dejarlo en `NONE`. El perfil `local` ya intenta asignar la tienda principal a la cuenta demo; no hace falta `MAIN_STORE_OWNER_EMAIL`.

En una base existente, cambiar contraseñas en `.env` no cambia las de MySQL. Conservar las que corresponden al volumen. No usar borrado de volúmenes para solucionar una discrepancia.

### 3.3 Levantar MySQL + backend + frontend

Desde la raíz:

```bash
docker info
docker compose version
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

El Compose actual ya incluye los tres servicios. Esperar a que termine el comando: la primera compilación y las migraciones tardan más que los siguientes arranques. No ejecutar simultáneamente `npm start` sobre 4300 ni un backend del host sobre 8080.

### 3.4 Comprobar backend

Desde la raíz, con los puertos de esta guía:

```bash
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Las tres respuestas deben indicar `{"status":"UP"}`. Readiness incluye la base de datos; liveness por sí sola no demuestra que MySQL esté disponible.

### 3.5 Comprobar frontend

Abrir **http://127.0.0.1:4300/**. Desde la terminal se pueden comprobar Nginx y su conexión al backend:

```bash
curl --fail http://127.0.0.1:4300/healthz
curl --fail http://127.0.0.1:4300/api/actuator/health/readiness
```

Ambas respuestas indican `UP`. La segunda usa el proxy real definido en `frontend/nginx.conf`.

### 3.6 Confirmar que el sistema está listo

Se ve la portada de merca.do, el icono de persona cuyo nombre accesible es **Ver cuenta**, el carrito y la sección **Piezas que merecen vitrina**. Sin login debe verse **Hola, visitante**. Localizar **Camiseta demo local**. Un health verde no acredita que la cuenta esté verificada: completar §4 y ensayar su login antes de presentar.

## 4. Datos de prueba

### 4.1 Qué existe automáticamente y qué no

| Cuenta / recurso | Credenciales y estado real | Uso |
| --- | --- | --- |
| Cuenta del perfil `local` | Correo `demo@marketplace.local`; contraseña inicial `MarketplaceDemo123!`; estado `ACTIVA`; roles `COMPRADOR`, `VENDEDOR`. **No se verifica automáticamente.** Si ya existía, el aprovisionador no restablece su contraseña/estado/roles. | Usar solo si el login ya funciona con correo verificado en esa base. |
| Producto local | `Camiseta demo local`, precio inicial 25000.00, stock inicial 100, categoría `Ropa`, activo. ID generado: no asumir uno fijo. | Una unidad para la compra. Si ya existe, el aprovisionador no reactiva ni repone stock. |
| Tienda principal | ID 1; el perfil local intenta asignarla a la cuenta demo sin sustituir a una dueña existente. | Necesaria si se quiere demostrar preparación como vendedor de ese pedido. |
| Fixture del E2E CU-08 | `cu08-real@example.test` / `Cu08RealTestPassword!`; ACTIVA, correo verificado, solo COMPRADOR. | La crea `scripts/cu08-real-e2e.sh` en su base desechable, no en la base de esta demo. No permite demostrar cambio entre dos roles. |
| Cuenta de presentación alternativa | Correo de un buzón accesible y contraseña elegidos al registrarla; ACTIVA, COMPRADOR al crearse y VENDEDOR tras confirmar el registro. | Prepararla con §4.3 si la cuenta local no puede entrar. No hay credenciales predeterminadas para ella. |

No se crean pedidos ni direcciones personales con `local`. La dirección y el pedido se generan desde el checkout de §7. No ejecutar seeds de otros CU esperando que solucionen la verificación. El seed SQL de CU-08 indica expresamente que se usa solo dentro de su base desechable.

### 4.2 Comprobar la cuenta local antes de elegirla

Abrir **Ver cuenta**, introducir las credenciales locales y pulsar **Iniciar sesión**. Si aparece **Seguridad de tu cuenta**, usar esa cuenta en la guía. Si aparece **Debes verificar tu correo antes de iniciar sesión. Puedes reenviar el código o ingresar el recibido.**, el problema es la marca de verificación; no es un error de Docker ni de contraseña.

El código `LocalDemoAccountConfiguration` guarda email, BCrypt, estado y roles, pero no `email_verified_at`. V28 marcó como verificadas las cuentas que existían cuando se aplicó; eso puede explicar por qué funciona una base antigua. En una base nueva, el runner local crea la cuenta después de las migraciones. V31 tampoco verifica cuentas; elimina sesiones antiguas de cuentas pendientes. El dominio `.local` no constituye un buzón entregable suministrado por el repositorio. No se puede prometer una instalación nueva sin SMTP que complete todos los flujos manuales con esa cuenta. Usar una cuenta existente verificada o preparar la alternativa siguiente.

### 4.3 Preparar una cuenta mediante los mecanismos existentes

Hacerlo antes de la sustentación. En `.env`, configurar con los valores reales del proveedor las variables ya soportadas: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` (587 por defecto), `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `EMAIL_VERIFICATION_FROM`; añadir `PASSWORD_RECOVERY_FROM` si se probará recuperación. Los remitentes deben estar autorizados. Se exige autenticación y STARTTLS; Compose no incluye un buzón de pruebas. No copiar dominios de ejemplo como si fueran un servicio SMTP.

Después de editar esa configuración, desde la raíz:

```bash
docker compose up -d --wait backend frontend
```

#### Paso P1 — Registrar la cuenta de presentación

**Acción:** En una ventana sin sesión, ir al bloque vendedor al final de la portada y abrir el registro.

**Dónde:** **Conocer el espacio vendedor** → **Vende en el marketplace** → **Soy nuevo**.

**Qué pulsar/escribir:** **Correo**: un buzón que puedas consultar; **Contraseña (mínimo 12 caracteres)** y **Repite la contraseña**: la contraseña que elijas, máximo 72 bytes UTF-8; **Nombre de tu tienda (único)**: un nombre libre elegido por ti. Marcar **Acepto las condiciones para vender** y pulsar **Crear cuenta y tienda**. Estos valores son datos nuevos introducidos por el presentador, no fixtures del repositorio.

**Resultado esperado:** **Confirma tu registro** y un correo con el código. Si hay fallo de entrega, la cuenta puede haber quedado creada: usar reenvío, no intentar crearla otra vez.

**Qué demuestra:** Preparación con el registro existente CU-12; la verificación posterior corresponde a CU-08.

#### Paso P2 — Verificar el correo y comprobar los roles

**Acción:** Consultar el buzón y copiar el último código recibido antes de 30 minutos.

**Dónde:** Registro, **Confirma tu registro**.

**Qué pulsar/escribir:** Pegar en **Código de verificación**, pulsar **Confirmar correo** y luego **Entendido**. Abrir **Ver cuenta** e iniciar sesión con el correo/contraseña elegidos.

**Resultado esperado:** Confirmación del registro y, al entrar, **Seguridad de tu cuenta**, identidad correcta y roles COMPRADOR/VENDEDOR. Si necesitas reenviar, escribir **Contraseña para reenviar** y pulsar **Reenviar verificación**; usar solo el nuevo código.

**Qué demuestra:** Correo confirmado y concesión del rol vendedor mediante el flujo existente.

En el resto de esta guía, **cuenta de presentación** significa esta cuenta o la demo local ya verificada. Usar exactamente la misma en A y B. La cuenta alternativa puede comprar la camiseta de la tienda principal, pero no administrar pedidos de esa tienda; para ese caso opcional ver §7, flujo E.

### 4.4 Preparación de carrito y pedido

Entrar como COMPRADOR. Abrir **Abrir carrito**; si hay artículos de ensayos propios, pulsar **Eliminar** en cada uno hasta ver **Tu carrito está vacío**. No borrar datos ajenos. Cerrar con **Cerrar carrito**. Comprobar que la camiseta se puede agregar; no asumir que un reinicio recupera su stock.

Para preparar evidencia de reserva, completar §7 hasta obtener un pedido **Confirmado**, anotar su número y dejarlo sin cancelar. No marcarlo listo para despacho. Cada ensayo de cancelación necesita un pedido nuevo: un cancelado no vuelve a confirmado.

## 5. CU-08 — Gestión de acceso, roles y sesiones

### 5.1 Qué demuestra CU-08

El backend autentica una cuenta activa y verificada, mantiene la sesión JDBC con cookie `SESSION` HttpOnly y exige el rol activo para autorizar operaciones. La UI permite cambiar entre roles asignados y gestionar sesiones propias. Revocar una sesión elimina ese acceso en el servidor; la otra ventana lo detecta en su siguiente petición protegida.

**Convención fija:** A = Chrome normal, sesión principal; B = Chrome incógnito, sesión secundaria. Cerrar previamente otras ventanas de incógnito para que B no herede una sesión de ensayo. Dos pestañas de una misma ventana comparten cookie y no acreditan dos sesiones independientes.

### Flujo A — Inicio de sesión

#### Paso 1 — Abrir Marketplace y la cuenta en A

**Acción:** Abrir `http://127.0.0.1:4300/` en A.

**Dónde:** Barra superior, icono de persona.

**Qué pulsar/escribir:** **Ver cuenta** (nombre accesible del icono).

**Resultado esperado:** Panel **Acceso a tu cuenta**, campos **Correo**, **Contraseña** y botón **Iniciar sesión**.

**Qué demuestra:** Entrada anónima al sistema.

#### Paso 2 — Autenticarse en A

**Acción:** Introducir la cuenta de presentación preparada en §4.

**Dónde:** Panel **Acceso a tu cuenta**, ventana A.

**Qué pulsar/escribir:** Escribir el correo en **Correo**, su contraseña en **Contraseña**, dejar desmarcado **Mantener la sesión iniciada** y pulsar **Iniciar sesión**. Para la demo local ya verificada: `demo@marketplace.local` / `MarketplaceDemo123!`, si no se cambió su contraseña.

**Resultado esperado:** **Seguridad de tu cuenta**, **Hola, <correo>**, **Roles disponibles:**, **Rol activo:** y **Cerrar sesión**. Con varios roles puede aparecer **Sin seleccionar** y **Sesión iniciada. Selecciona tu rol activo.**

**Qué demuestra:** Login real; tener roles asignados no equivale a haber seleccionado uno.

### Flujo B — Rol activo

#### Paso 3 — Elegir VENDEDOR en A

**Acción:** Mostrar los dos roles y cambiar el rol activo.

**Dónde:** **Seguridad de tu cuenta**, A.

**Qué pulsar/escribir:** Abrir **Cambiar rol activo**, elegir **VENDEDOR** y confirmar **OK** en el diálogo Ionic. Si la etiqueta intercepta el clic, enfocar el selector con Tab y pulsar Espacio.

**Resultado esperado:** **Rol activo: VENDEDOR** y **Rol activo actualizado.** Al cerrar con **Cerrar panel**, aparece **Pedidos recibidos**; **Mis pedidos** no aparece con ese rol.

**Qué demuestra:** Cambio explícito de contexto de permisos. No concede un rol nuevo.

#### Paso 4 — Volver a COMPRADOR en A

**Acción:** Reabrir **Ver cuenta** si se cerró el panel.

**Dónde:** Selector **Cambiar rol activo**, A.

**Qué pulsar/escribir:** Elegir **COMPRADOR** → **OK**. Cerrar con **Cerrar panel**.

**Resultado esperado:** **Rol activo: COMPRADOR** antes de cerrar y botón **Mis pedidos** en la barra superior.

**Qué demuestra:** Acceso de comprador dependiente del rol activo.

### Flujo C — Dos sesiones reales y revocación selectiva

#### Paso 5 — Crear B sin cerrar A

**Acción:** Abrir una ventana de incógnito desde el menú de Chrome. Mantener A abierta. En B visitar exactamente `http://127.0.0.1:4300/`.

**Dónde:** Solo ventana B.

**Qué pulsar/escribir:** **Ver cuenta**, mismo **Correo** y **Contraseña** que A, **Iniciar sesión**; seleccionar COMPRADOR como en el paso 4. Abrir **Sesiones activas**.

**Resultado esperado:** B está autenticada con la misma cuenta. Su lista identifica **Esta es tu sesión actual** y **Otra sesión**. Anotar el **Identificador** marcado como actual en B para reconocerlo desde A; no es la cookie de autenticación.

**Qué demuestra:** Dos inicios de sesión con almacenes de cookies separados.

#### Paso 6 — Identificar las dos sesiones desde A

**Acción:** Volver a A. No tocar B.

**Dónde:** A → **Ver cuenta** → **Sesiones activas**.

**Qué pulsar/escribir:** **Actualizar**. Comparar el identificador anotado en B con la fila **Otra sesión** de A.

**Resultado esperado:** A tiene una fila **Esta es tu sesión actual** y otra cuyo identificador coincide con el actual de B. También se muestran **Creada**, **Último acceso** y **Expiración**. Si aparecen más sesiones, usar el identificador para evitar confundirlas.

**Qué demuestra:** Listado de sesiones propias y distinción de la actual.

#### Paso 7 — Revocar únicamente B desde A

**Acción:** En A, actuar sobre la fila identificada como B.

**Dónde:** A → **Sesiones activas** → fila **Otra sesión** correspondiente a B.

**Qué pulsar/escribir:** **Revocar sesión**, comprobar el identificador en **Confirmar revocación**, pulsar **Sí, revocar**.

**Resultado esperado:** **Sesión revocada.** La fila B desaparece; A conserva su identidad. No pulsar **Revocar esta sesión**, que corresponde a A.

**Qué demuestra:** Revocación individual en backend, no logout global.

#### Paso 8 — Probar el rechazo de B

**Acción:** Volver a B, cuyo panel **Sesiones activas** quedó abierto. Abrir DevTools de Chrome → Network/Red y dejar visible la captura de peticiones.

**Dónde:** Solo B.

**Qué pulsar/escribir:** En la aplicación, **Actualizar**. En Network filtrar por `auth/sessions` y seleccionar la petición GET.

**Resultado esperado:** `GET /api/auth/sessions` responde **401**. La UI pasa a **Acceso a tu cuenta** y muestra **La sesión no está disponible o las credenciales son incorrectas. Inicia sesión para continuar.** Recargar B: sigue como **Hola, visitante**; no aparece **Mis pedidos**. No es necesario volver a introducir credenciales.

**Qué demuestra:** La sesión revocada pierde acceso en su siguiente petición. No hay actualización espontánea por WebSocket; una pantalla que aún conserve datos antes de pedir al backend no prueba que la sesión siga vigente.

#### Paso 9 — Confirmar que A continúa activa

**Acción:** Volver a A después del 401 de B.

**Dónde:** A → **Sesiones activas**.

**Qué pulsar/escribir:** **Actualizar**; cerrar **Cerrar panel** y abrir **Mis pedidos**.

**Resultado esperado:** Lista de sesiones válida y consulta de pedidos permitida, aunque esté vacía y diga **Todavía no tienes pedidos.** A conserva COMPRADOR.

**Qué demuestra:** Revocar B no invalida A.

### Flujo D — Logout

#### Paso 10 — Cerrar sesión en A

**Acción:** Cerrar pedidos con **Cerrar pedidos** y abrir **Ver cuenta**.

**Dónde:** A → **Seguridad de tu cuenta**.

**Qué pulsar/escribir:** **Cerrar sesión**.

**Resultado esperado:** Se cierra el panel; aparece **Hola, visitante** y desaparece **Mis pedidos**. Si se observa Network, `POST /api/auth/logout` responde 204. Si la petición falla, la aplicación no debe fingir que cerró sesión.

**Qué demuestra:** Logout confirmado por el backend.

#### Paso 11 — Comprobar anonimato tras recargar A

**Acción:** Recargar A y abrir **Ver cuenta**.

**Dónde:** A, misma URL.

**Qué pulsar/escribir:** Recargar del navegador; **Ver cuenta**.

**Resultado esperado:** **Acceso a tu cuenta** y **Iniciar sesión**, sin identidad autenticada. Network muestra `GET /api/auth/me` → 401 durante la restauración. El catálogo público sigue accesible.

**Qué demuestra:** La recarga no recupera una sesión cerrada. Para CU-11 volver a iniciar sesión y seleccionar COMPRADOR.

### Pruebas manuales adicionales — no recomendadas para la demo de 20 min

#### Paso 12 — Sesión persistente

**Acción:** En A anónima, iniciar sesión marcando **Mantener la sesión iniciada**; seleccionar COMPRADOR.

**Dónde:** **Acceso a tu cuenta**, A.

**Qué pulsar/escribir:** Credenciales de presentación, checkbox, **Iniciar sesión**. Cerrar completamente Chrome normal y volver a abrir la misma URL con el mismo perfil, sin borrar cookies.

**Resultado esperado:** Se restaura la cuenta; **Ver cuenta** muestra la identidad. La configuración normal tiene 30 minutos de inactividad; la persistente, 7 días y cookie persistente. No esperar esos plazos durante la demo; la expiración está automatizada.

**Qué demuestra:** Persistencia voluntaria. La restauración automática de pestañas del navegador hace que cerrar/reabrir una sesión normal no sea una prueba fiable de expiración.

#### Paso 13 — Cerrar las demás sesiones

**Acción:** Volver a crear B como en paso 5.

**Dónde:** A → **Sesiones activas**.

**Qué pulsar/escribir:** **Cerrar las demás sesiones**. No tiene el diálogo de confirmación individual del paso 7.

**Resultado esperado:** **Las demás sesiones se cerraron. Esta sesión continúa activa.** Solo queda la actual; al pulsar **Actualizar** en B, B recibe 401.

**Qué demuestra:** Revocación de las otras sesiones de la misma cuenta preservando A.

#### Paso 14 — Revocar la sesión actual

**Acción:** Usar B autenticada para que A quede disponible como respaldo.

**Dónde:** B → **Sesiones activas** → **Esta es tu sesión actual**.

**Qué pulsar/escribir:** **Revocar esta sesión** → **Sí, revocar**.

**Resultado esperado:** B vuelve a login y muestra **Sesión actual revocada. Vuelve a iniciar sesión para continuar.** A conserva acceso.

**Qué demuestra:** Revocación del acceso que realiza la petición.

#### Paso 15 — Cambiar contraseña

**Acción:** Crear A y B autenticadas; tener guardada la contraseña actual y elegir una nueva distinta, de al menos 12 caracteres y máximo 72 bytes UTF-8.

**Dónde:** A → **Ver cuenta** → **Cambiar contraseña**.

**Qué pulsar/escribir:** **Contraseña actual**, **Nueva contraseña**, **Confirmar nueva contraseña** → **Guardar nueva contraseña**.

**Resultado esperado:** **Contraseña actualizada. Esta sesión continúa activa; las demás sesiones de tu cuenta se cerraron.** B pierde acceso en su siguiente **Actualizar**. Un nuevo login acepta la nueva contraseña.

**Qué demuestra:** Cambio autenticado y revocación de otras sesiones. Para dejar lista la presentación, cambiar de nuevo a la contraseña prevista usando este mismo formulario; reiniciar el backend no restaura la contraseña inicial.

#### Paso 16 — Correo pendiente, reenvío y confirmación

**Acción:** Usar la cuenta con buzón accesible de P1 antes de confirmar P2. Cerrar el registro y tratar de iniciar sesión con credenciales correctas.

**Dónde:** **Acceso a tu cuenta**.

**Qué pulsar/escribir:** **Iniciar sesión**; ante el aviso de correo pendiente, conservar o reescribir **Correo** y **Contraseña**, pulsar **Reenviar verificación**. Abrir **Verificar correo**, pegar el último token en **Código de verificación**, pulsar **Confirmar correo**.

**Resultado esperado:** Antes de verificar: rechazo 403 `EMAIL_NOT_VERIFIED` y aviso de verificación obligatoria. Después: **Correo verificado. Ya puedes iniciar sesión.** Hay que hacer login; verificar no inicia sesión automáticamente. Un token anterior/reutilizado no sirve.

**Qué demuestra:** Control real de correo y tokens de un solo uso. Requiere SMTP y buzón; la cuenta local ya verificada no sirve para repetir este escenario pendiente.

#### Paso 17 — Recuperar contraseña

**Acción:** Usar una cuenta ACTIVA cuyo correo llegue a un buzón accesible y configurar también `PASSWORD_RECOVERY_FROM`.

**Dónde:** Ventana anónima → **Ver cuenta** → **Olvidé mi contraseña**.

**Qué pulsar/escribir:** **Correo de la cuenta** → **Solicitar instrucciones**. Consultar el correo. Pulsar **Ingresar token recibido** (o **Ya tengo un token de recuperación** desde login), completar **Token de recuperación recibido**, **Nueva contraseña**, **Confirmar nueva contraseña**, pulsar **Recuperar contraseña**.

**Resultado esperado:** La solicitud muestra **Si la cuenta está activa, recibirás instrucciones para recuperar la contraseña.** No garantiza entrega. El token vence en 15 minutos; usar el último recibido. Al confirmar: **Contraseña recuperada. Puedes iniciar sesión con tu nueva contraseña.** Las sesiones anteriores de esa cuenta quedan invalidadas; comprobarlo mediante una petición en A/B y luego hacer login con la nueva contraseña.

**Qué demuestra:** Recuperación por token, cambio de BCrypt y revocación. Sin SMTP se conserva la respuesta genérica, pero no hay token utilizable; mostrar la prueba automatizada, no afirmar recuperación completada.

## 6. Evidencia automatizada de CU-08

### Backend

Rutas de la tabla relativas a `backend/demo/src/test/java/com/transformersas/marketplace/auth/`.

| Archivo | Tipo | Qué demuestra |
| --- | --- | --- |
| `SessionAuthenticationTests.java` | Integración Spring Security/MockMvc + MySQL | Login, credenciales, CSRF, rol, logout, sesiones, cambio/recuperación y concurrencia de token. |
| `PersistentSessionIntegrationTests.java` | Integración JDBC | Cookie normal/persistente, 30 min/7 días, expiración, revocación y conservación de la actual. |
| `CurrentAccountSessionIntegrationTests.java` | Integración | Cuenta desactivada/eliminada y cambios de roles después de login; permisos actuales. |
| `EmailVerificationIntegrationTests.java` | Integración MySQL + GreenMail | Rechazo sin verificación, envío, token, reenvío, consumo único, expiración y fallo SMTP. |
| `PasswordRecoverySmtpIntegrationTests.java` | Integración MySQL + GreenMail | Correo real al servidor local de prueba, recuperación, hash, respuesta genérica y revocación. |
| `BuyerIsolationIntegrationTests.java`, `BuyerOwnershipMigrationTests.java` | Integración / migración | Propiedad de carrito, direcciones, reservas y pagos; filas anteriores sin dueño. |
| `RemainingAccessBlockersTests.java` | Integración | Permisos de creación de producto e identidad obtenida de la sesión. |
| `SessionSellerActorProviderTests.java` | Unitaria con colaboradores simulados | Rol vendedor y tienda propia. |
| `application/usecase/ManageAccountSessionsTests.java` | Unitaria con repositorio simulado | Filtrado/revocación de sesiones propias. |
| `application/usecase/RecoverAccountPasswordValidationTests.java` | Unitaria | Validación antes de acceder a repositorios/notificaciones. |
| `infrastructure/notification/RecoveryNotificationConfigurationTests.java`, `infrastructure/notification/SmtpEmailVerificationNotifierTests.java` | Configuración / adaptador | Transporte/remitentes y fallo explícito si faltan. |

Requiere JDK 21 y Docker activo. Desde la raíz, ejecutar en subshell para conservar la carpeta de la terminal:

```bash
(
  cd backend/demo
  ./mvnw -Dtest=SessionAuthenticationTests,PersistentSessionIntegrationTests,CurrentAccountSessionIntegrationTests,EmailVerificationIntegrationTests,PasswordRecoverySmtpIntegrationTests,BuyerIsolationIntegrationTests,BuyerOwnershipMigrationTests,RemainingAccessBlockersTests,SessionSellerActorProviderTests,ManageAccountSessionsTests,RecoverAccountPasswordValidationTests,RecoveryNotificationConfigurationTests,SmtpEmailVerificationNotifierTests test
)
```

Para una fila concreta sustituir la lista de `-Dtest=` por su nombre de clase sin `.java`. Resultado esperado: `BUILD SUCCESS`, sin fallos/errores y sin pruebas omitidas por falta de Docker. Los reportes se generan en `backend/demo/target/surefire-reports/`. Testcontainers usa su MySQL temporal; MockMvc no es un navegador ni un E2E HTTP externo. GreenMail no acredita entrega a un proveedor real.

### Frontend / Playwright con API interceptada

| Archivo en `frontend/e2e/` | Qué valida |
| --- | --- |
| `email-verification.spec.ts` | Aviso de correo pendiente, reenvío, fallo de entrega y confirmación. |
| `persistent-session.spec.ts` | Checkbox rememberMe y restauración/limpieza del estado. |
| `logout.spec.ts` | Éxito y errores de logout, red, 401 y CSRF; no finge cierre ante fallo. |
| `revoke-other-sessions.spec.ts` | Cierre de otras sesiones y manejo de errores. |
| `remaining-access-blockers.spec.ts` | Selección explícita del rol y limpieza del carrito entre cuentas. |

Estas pruebas usan `page.route('**/api/**', ...)`: prueban la interfaz real con respuestas HTTP sustituidas. No son E2E reales contra MySQL. `home.spec.ts` es portada general, no prueba específica de CU-08. No hay una suite unitaria CU-08 separada equivalente a `tests/mis-pedidos.test.cjs`.

Preparación opcional con Node 22/npm, desde la raíz:

```bash
(
  cd frontend
  npm ci
  npx playwright install --with-deps chrome
)
```

Para garantizar que Playwright sirva el frontend de este checkout, liberar antes 4300 y luego ejecutar las pruebas en `frontend`:

```bash
docker compose stop frontend
(
  cd frontend
  npx playwright test e2e/email-verification.spec.ts e2e/persistent-session.spec.ts e2e/logout.spec.ts e2e/revoke-other-sessions.spec.ts e2e/remaining-access-blockers.spec.ts --project=chrome
)
docker compose up -d --wait frontend
```

Resultado esperado: casos `passed`, sin `failed`. No ejecutar este bloque durante la demostración: interrumpe temporalmente el frontend. La configuración inicia `npm start` sobre 4300 y permite reutilizar un servidor existente; por eso se explicita la liberación del puerto. No usar `npm test` como sinónimo de estas pruebas.

### E2E real propio de este repositorio

Archivo `frontend/e2e-real/account-session.spec.ts`; infraestructura `compose.e2e.yaml`, `scripts/cu08-real-e2e.sh`, `scripts/cu08-real-seed.sql`, `frontend/playwright.real.config.ts`.

Con Node/dependencias/Chrome preparados y Docker activo, desde la raíz:

```bash
bash scripts/cu08-real-e2e.sh
```

Resultado esperado: **1 caso aprobado**, login desde UI, `/api/auth/me` 200, cookie HttpOnly, logout UI 204, `/me` 401 y rechazo 401 al reutilizar la cookie anterior. Recorrido **navegador → frontend/Nginx → backend → MySQL**, sin `page.route()` ni respuestas HTTP sustituidas. No cubre el cambio de roles ni dos sesiones desde la UI.

Usa 14300 por defecto (`CU08_E2E_PORT` permite cambiarlo), proyecto Compose independiente y `.env` desactivado mediante `--env-file /dev/null`. Su limpieza elimina únicamente los volúmenes de su proyecto desechable. No usarlo para aprovisionar la demo manual. Informes: `frontend/playwright-report-real/` y `frontend/test-results-real/`.

### Qué ejecuta realmente CI

`.github/workflows/backend-ci.yml` se activa por push a ramas. `validate` ejecuta `./mvnw clean verify`; `frontend-e2e` instala Node 22/Chrome, construye frontend, ejecuta Playwright normal Chrome y el script real CU-08. `build`/`publish` dependen de `validate`, no de `frontend-e2e`. La existencia del workflow no acredita una ejecución verde del commit presentado. Ver también `docs/pruebas/cu-08-autenticacion-y-sesiones.md`.

## 7. CU-11 — Consulta y cancelación de pedidos

### 7.1 Qué demuestra CU-11

Un COMPRADOR autenticado consulta solo sus pedidos y cancela los propios en `CONFIRMED` o `IN_PREPARATION`. La cancelación pasa directamente a `CANCELLED`, registra motivo, restaura inventario y procesa el reembolso. El estado cancelado no se revierte si el resultado financiero queda pendiente.

Para el recorrido manual usar A autenticada como COMPRADOR, carrito vacío y producto disponible. B queda anónima después de CU-08. Los pagos y reembolsos usan los adaptadores simulados del backend (`MockPaymentGateway` y `SimulatedRefundGateway`): no hay cobro bancario real ni formulario de número de tarjeta.

### Flujo A — Crear u obtener un pedido cancelable

#### Paso 18 — Entrar como comprador y localizar el producto

**Acción:** Si se hizo logout de CU-08, repetir pasos 1–2 y seleccionar COMPRADOR. Cerrar la cuenta y recargar para volver a cargar el catálogo autenticado.

**Dónde:** A → **Piezas que merecen vitrina**.

**Qué pulsar/escribir:** Localizar **Camiseta demo local** en esa sección, no en **Recomendado para ti**. Si hace falta, usar el buscador **¿Qué estás buscando hoy?** y Enter.

**Resultado esperado:** Tarjeta del producto con precio y **Agregar**. El precio inicial del seed es $25.000; si la base fue modificada, comparar los importes realmente mostrados, no asumir el valor inicial.

**Qué demuestra:** Preparación de una compra real sobre el catálogo disponible.

#### Paso 19 — Agregar una unidad y revisar el carrito

**Acción:** Agregar exactamente una unidad.

**Dónde:** Tarjeta de **Camiseta demo local**, luego barra superior.

**Qué pulsar/escribir:** **Agregar** una vez → **Abrir carrito**.

**Resultado esperado:** Panel **Carrito**, producto correcto, cantidad 1, precio/subtotal. Si se duplicó por un ensayo, usar **Disminuir cantidad**; **Eliminar** quita la línea. No continuar con productos ajenos al ensayo.

**Qué demuestra:** Carrito de la cuenta autenticada como insumo del pedido.

#### Paso 20 — Abrir checkout y completar la dirección

**Acción:** Continuar la compra desde el carrito.

**Dónde:** A → **Carrito** → panel **Finalizar compra**, título **Checkout**.

**Qué pulsar/escribir:** **Continuar compra**. En **Escribe tu dirección de entrega**, escribir `Calle 123 # 45-67, pruebas E2E` (texto usado por la prueba real referenciada; se crea al comprar, no viene precargado).

**Resultado esperado:** Dirección escrita en **Dirección de entrega**. No hay pantalla adicional de destinatario/teléfono: el frontend envía `Comprador`, `Bogotá`, `Bogotá D.C.` y `333-333-3333` como datos generales del prototipo cuando calcula el total.

**Qué demuestra:** Preparación de la dirección propia que se asociará al pedido.

#### Paso 21 — Seleccionar envío y calcular

**Acción:** Elegir el método estándar y dejar el cupón vacío.

**Dónde:** A → **Checkout** → **Método de envío**.

**Qué pulsar/escribir:** **Estándar** → **Calcular total**.

**Resultado esperado:** **Resumen de compra** con subtotal, envío y total. Con una camiseta inicial de 25000, envío STANDARD 10000 y sin descuentos, total 35000. Si modificas dirección, envío o contenido, volver a calcular antes de confirmar.

**Qué demuestra:** El backend crea la dirección y calcula el checkout; no basta con escribir la dirección.

#### Paso 22 — Pagar y anotar el pedido

**Acción:** Usar el camino aprobado del adaptador de pagos.

**Dónde:** A → resumen del checkout → **Método de pago**.

**Qué pulsar/escribir:** Elegir **Tarjeta** → **Confirmar compra**, una sola vez; esperar el resultado.

**Resultado esperado:** **✓ ¡Compra confirmada!**, **Tu pedido fue creado correctamente.**, número **#N**, **Total pagado** y **Transacción:**. Anotar N, total y transacción antes de cerrar con **Cerrar checkout**. No elegir **Simular pago rechazado**, **Simular pago pendiente** ni **Simular pasarela no disponible** para esta ruta.

**Qué demuestra:** Creación de un pedido confirmado del comprador tras pago aprobado. Las reservas, el pago y la limpieza del carrito los coordina el sistema; no hay botones adicionales para esos pasos.

### Flujo B — Mis pedidos

#### Paso 23 — Localizar el pedido creado

**Acción:** Abrir la lista y buscar el mismo número anotado.

**Dónde:** A → barra superior → **Mis pedidos**.

**Qué pulsar/escribir:** **Mis pedidos**; si el panel ya estaba abierto, **Actualizar pedidos**. En **Pedido #N**, pulsar **Ver detalle** (nombre accesible **Ver detalle del pedido N**).

**Resultado esperado:** Detalle del mismo N, estado **Confirmado**, total, envío `STANDARD`, fecha, **Dirección de envío registrada #...**, **Transacción:** y sección **Productos**.

**Qué demuestra:** Consulta del pedido propio. No elegir simplemente el primer pedido de una lista con ensayos anteriores.

#### Paso 24 — Comparar los datos

**Acción:** Comparar el detalle con la compra que se acaba de hacer.

**Dónde:** A → detalle de **Pedido #N**.

**Qué pulsar/escribir:** No modificar datos. Mostrar producto, **Cantidad: 1**, **Precio unitario:**, **Subtotal:**, total y transacción. Si se quiere acreditar el código técnico, en Network abrir la respuesta de `GET /api/orders/N`.

**Resultado esperado:** Coinciden número, transacción e importes con checkout. El formato cambia: checkout usa moneda sin decimales y pedidos dos decimales. La respuesta tiene `status: "CONFIRMED"`; la UI dice **Confirmado**. El detalle muestra el identificador de dirección, no la dirección completa.

**Qué demuestra:** Persistencia y consistencia del detalle.

### Flujo C — Cancelación

#### Paso 25 — Abrir la cancelación y elegir Otro

**Acción:** Iniciar cancelación sin confirmar todavía.

**Dónde:** A → detalle de **Pedido #N**.

**Qué pulsar/escribir:** **Cancelar pedido**. En **Motivo de cancelación**, elegir **Otro**. La otra opción actual es **Ya no quiero el pedido**.

**Resultado esperado:** Aparece **Explica el motivo (obligatorio)** y **Debes explicar el motivo para continuar.** **Confirmar cancelación** está deshabilitado sin motivo y también con Otro sin explicación. Escribir solo espacios mantiene el bloqueo.

**Qué demuestra:** Validación de motivo y explicación obligatoria para `OTHER`.

#### Paso 26 — Confirmar la cancelación definitiva

**Acción:** Completar la explicación y confirmar el pedido correcto.

**Dónde:** A → formulario de cancelación del mismo N.

**Qué pulsar/escribir:** En **Explica el motivo (obligatorio)**, escribir `Compré la talla equivocada; cancelación de prueba CU-11.`. Leer **¿Confirmas la cancelación definitiva del pedido #N?** y pulsar **Confirmar cancelación**. **Volver** permite abandonar antes de confirmar.

**Resultado esperado:** Estado **Cancelado** y, con la configuración predeterminada, **Pedido cancelado. Reembolso completado**. Desaparecen **Cancelar pedido** y **Confirmar cancelación**. En Network, `POST /api/orders/N/cancellation` responde 200 con `status: "CANCELLED"`, `paymentStatus: "REFUNDED"`, `refund.status: "COMPLETED"`.

**Qué demuestra:** Transición directa a CANCELLED y resultado de reembolso; no requiere una aprobación posterior del vendedor. La interfaz transmite `reasonCode: "OTHER"` y la explicación recortada, máximo 1000 caracteres.

Si el backend devuelve reembolso pendiente, la UI muestra **Pedido cancelado. Reembolso en proceso** y el pedido sigue Cancelado. No repetir la cancelación para intentar cobrar/reembolsar otra vez. La pasarela de reembolsos es simulada: “completado” acredita su resultado registrado, no una transferencia bancaria.

### Flujo D — Persistencia

#### Paso 27 — Recargar y volver al mismo pedido

**Acción:** Anotar/retener N y recargar A.

**Dónde:** A → **Mis pedidos** → **Pedido #N**.

**Qué pulsar/escribir:** Recargar; **Mis pedidos** → **Ver detalle** del mismo N.

**Resultado esperado:** **Cancelado**, mismo número, importes y productos, sin **Cancelar pedido**. La nueva respuesta GET conserva `status: "CANCELLED"`. El mensaje temporal **Pedido cancelado. Reembolso completado** puede desaparecer: el GET de detalle no incluye `refund` ni `paymentStatus` y la UI no los reconstruye tras recargar.

**Qué demuestra:** Estado persistido en MySQL, no solo un cambio de texto en memoria. Mostrar el reembolso antes de recargar y la persistencia del estado después.

### Flujo E — IN_PREPARATION (opcional, fuera de la ruta corta)

Existe un mecanismo UI seguro: **Iniciar preparación** en el panel vendedor. Requiere un **nuevo** pedido Confirmado y acceso verificado de la dueña de la tienda de ese pedido. La cuenta alternativa de §4.3 posee otra tienda: no puede preparar pedidos de la tienda principal. Si solo dispones de ella, omitir esta prueba manual y mostrar `OrderOwnershipIntegrationTests.cancellationChangesOnlyTheOwnedOrderAndRepeatReturns409`, parametrizado con `CONFIRMED` e `IN_PREPARATION`, más `SellerStartPreparationTests`.

#### Paso 28 — Pasar un nuevo pedido a preparación

**Acción:** Crear otro pedido siguiendo 18–22 y anotar su número. Con la cuenta dueña de la tienda (la demo local, si quedó correctamente asignada y está verificada), cambiar a VENDEDOR.

**Dónde:** **Ver cuenta** → **Cambiar rol activo**; después cerrar la cuenta y abrir **Pedidos recibidos**.

**Qué pulsar/escribir:** VENDEDOR → **OK** → **Cerrar panel** → **Pedidos recibidos**. En **N.º de pedido**, escribir el nuevo número y pulsar **Buscar**. Pulsar la fila **Pedido #N** de la lista filtrada para abrir el detalle; allí pulsar **Iniciar preparación**.

**Resultado esperado:** **Pedido en preparación.** y estado **En preparación**. Si devuelve falta de permiso/no encontrado, comprobar la dueña: no cambiarla en SQL para forzar la demo.

**Qué demuestra:** Preparación real por el vendedor mediante CU-23, como preparación de datos para CU-11.

#### Paso 29 — Cancelar desde comprador en preparación

**Acción:** Cerrar **Pedidos recibidos** con **Cerrar** y volver a la cuenta compradora del pedido; si es la misma cuenta, basta cambiar su rol a COMPRADOR.

**Dónde:** Cuenta compradora → **Mis pedidos** → detalle del nuevo número.

**Qué pulsar/escribir:** **Ver detalle**, comprobar **En preparación**, luego repetir pasos 25–27.

**Resultado esperado:** CANCELLED / **Cancelado**, reembolso informado, ausencia de segunda cancelación y persistencia.

**Qué demuestra:** La cancelación del comprador admite también IN_PREPARATION.

### Flujo F — Errores relevantes

No invertir el tiempo de exposición en crear cuentas ajenas o manipular estados. Todas estas comprobaciones tienen pruebas en `backend/demo/src/test/java/com/transformersas/marketplace/orders/OrderOwnershipIntegrationTests.java`:

| Escenario | Respuesta | Método de prueba |
| --- | --- | --- |
| Detalle ajeno/no existente/sin dueño | 404 | `foreignHistoricalAndMissingOrdersAllReturn404` |
| Cancelación ajena incluso suplantando accountId | 404 | `cancellationHidesForeignHistoricalAndMissingOrdersDespiteSpoofedAccount` |
| Estado incompatible | 409, sin efectos | `cancellationRejectsNonCancelableStatesWithoutEffects` |
| Segunda cancelación | 409 | `cancellationChangesOnlyTheOwnedOrderAndRepeatReturns409` |
| Sin COMPRADOR activo | 403 | `cancellationRequiresActiveBuyerRole`, `onlyActiveBuyerRoleAllowsBothGetAndHead` |
| Anónimo / sin CSRF | 401 / 403 | `cancellationRequiresAuthenticationAndCsrf`, `orderQueriesRequireAuthentication` |

Si un conflicto sucede durante el uso manual, la UI muestra **El pedido ya no está en un estado cancelable. Actualiza el detalle.** y ofrece **Actualizar detalle**. Ante 404 muestra **Pedido no disponible o no encontrado.** y retira el detalle; ante 403, **No tienes permiso para esta operación. Comprueba tu rol activo.** No interpretar un 403 por CSRF como demostración automática de un rechazo por rol.

## 8. Evidencia automatizada de CU-11

### Backend

| Archivo | Tipo y verificaciones |
| --- | --- |
| `backend/demo/src/test/java/com/transformersas/marketplace/orders/OrderOwnershipIntegrationTests.java` | Spring/MockMvc + MySQL Testcontainers: propiedad de compra/lista/detalle, cancelación desde ambos estados, Otro obligatorio, stock, registro del motivo/historial, notificaciones, reembolso completado/pendiente, concurrencia (200/409), persistencia y permisos. |
| `backend/demo/src/test/java/com/transformersas/marketplace/orders/CheckoutOrderCreationTests.java` | Integración de creación del pedido durante checkout; evidencia complementaria para obtener el pedido. |
| `backend/demo/src/test/java/com/transformersas/marketplace/orders/SellerStartPreparationTests.java` | Integración de la transición de preparación del vendedor; preparación del escenario opcional. |
| `backend/demo/src/test/java/com/transformersas/marketplace/payments/RequestRefundUseCaseTests.java` | Integración del servicio compartido de reembolso y su persistencia; pasarela simulada. |
| `backend/demo/src/test/java/com/transformersas/marketplace/payments/RefundOrderTotalCapTests.java` | Integración: el total reembolsado no supera el pagado. |

Con Java 21/Docker, desde la raíz:

```bash
(
  cd backend/demo
  ./mvnw -Dtest=OrderOwnershipIntegrationTests,CheckoutOrderCreationTests,SellerStartPreparationTests,RequestRefundUseCaseTests,RefundOrderTotalCapTests test
)
```

Para el respaldo rápido de CU-11, basta:

```bash
(
  cd backend/demo
  ./mvnw -Dtest=OrderOwnershipIntegrationTests test
)
```

Resultado esperado: `BUILD SUCCESS`, sin fallos/errores/omitidas. Reportes Surefire en `backend/demo/target/surefire-reports/`. CI ejecuta estas clases como parte de Maven `clean verify`; no se presupone que su última ejecución haya pasado.

### Prueba frontend sin navegador

`frontend/tests/mis-pedidos.test.cjs` transpila el TypeScript real y sustituye Angular/DI, servicio y componentes hijos. Comprueba ambos estados cancelables, confirmación y motivo obligatorios, explicación de Otro, actualización de lista/detalle, ocultación lógica del botón, reembolso pendiente, 409, endpoint/cuerpo y enlaces de la plantilla.

Con Node 22 y dependencias instaladas, desde la raíz:

```bash
(
  cd frontend
  node --test tests/mis-pedidos.test.cjs
)
```

Resultado esperado: 8 pruebas aprobadas, 0 fallos. **No es una prueba de navegador ni un E2E**. No está conectada a un script propio de `package.json` ni aparece ejecutada en el workflow actual; no afirmar que CI la ejecuta mediante Playwright o `npm test`.

### Playwright / E2E real: referencia externa

En Transformers-AS no hay un spec Playwright propio de CU-11. La referencia real inspeccionada está en el repositorio vecino **Transformers-Integration-Tests**, archivo `tests/e2e/checkout-order-cancellation.spec.ts`, con `tests/helpers/account.ts`, `playwright.config.ts` y `package.json`.

Ese caso recorre **navegador → frontend → backend → MySQL**, compra desde la UI, verifica número/transacción/total, selecciona Otro, exige explicación incluso frente a espacios, observa el POST real, verifica CANCELLED/REFUNDED/COMPLETED, ausencia del botón y GET persistido después de recargar. **No sustituye respuestas mediante mocks**; `waitForResponse` observa la petición real. El uso de pasarelas simuladas por el backend es un límite distinto de interceptar la API en Playwright.

Solo como referencia, si se dispone del repositorio vecino y del sistema manual ya preparado, el comando para sus credenciales predeterminadas es:

```bash
(
  cd ../Transformers-Integration-Tests
  npm ci
  npx playwright install --with-deps chromium
  FRONTEND_BASE_URL=http://127.0.0.1:4300 npm run test:e2e -- tests/e2e/checkout-order-cancellation.spec.ts --project=chromium
)
```

Su helper lee `E2E_EMAIL` y `E2E_PASSWORD`; por defecto usa la cuenta local. Para la cuenta alternativa, establecer esas dos variables en el entorno con el correo/contraseña realmente registrados antes de ejecutar. No hay credenciales alternativas fijas. Requiere carrito vacío y correo verificado; el test no crea ni verifica esa cuenta ni limpia un carrito previo. Su configuración no levanta el sistema. Resultado esperado: 1 caso aprobado e informe en `playwright-report/` de **ese otro repositorio**. El workflow de Transformers-AS no ejecuta este spec externo.

## 9. RUTA RECOMENDADA PARA LA SUSTENTACIÓN

Objetivo: **9–10 minutos** para los dos CU dentro de los 20 minutos totales. Antes de empezar: infraestructura lista, cuenta verificada, SMTP/registro ya resueltos si hicieron falta, carrito vacío, Chrome A anónimo y B preparado como contexto separado. Tener un pedido Confirmado de reserva anotado, sin cancelar, para Plan B.

| Minuto aprox. | Acción | Qué mostrar | Qué decir |
| --- | --- | --- | --- |
| 0:00–0:40 | Login en A | Identidad y roles | «La identidad proviene de la sesión del backend.» |
| 0:40–1:20 | VENDEDOR y volver a COMPRADOR | Rol activo y Mis pedidos | «La autorización exige el rol activo.» |
| 1:20–2:10 | Login de la misma cuenta en B incógnito | Segundo acceso e identificador actual | «Este navegador tiene un almacén de cookies separado.» |
| 2:10–3:00 | Revocar B desde A | Dos filas, confirmación, B desaparece | «Revoco únicamente esta sesión.» |
| 3:00–3:40 | Actualizar B y comprobar A | 401 de B y A todavía autenticada | «La siguiente petición protegida detecta la revocación.» |
| 3:40–4:10 | Logout A y recargar | Visitante, /me 401 | «El servidor invalida el acceso y no se restaura al recargar.» |
| 4:10–4:40 | Login A, COMPRADOR | Mis pedidos disponible | «Ahora uso el contexto comprador.» |
| 4:40–6:20 | Agregar camiseta, dirección, Estándar, Calcular total, Tarjeta, Confirmar compra | Confirmación y #N | «La compra aprobada crea el pedido de esta cuenta.» |
| 6:20–7:00 | Mis pedidos → detalle #N | Confirmado, datos y transacción | «Estos datos corresponden al pedido persistido.» |
| 7:00–8:00 | Cancelar → Otro vacío → explicación → confirmar | Botón bloqueado y después Cancelado/reembolso | «Otro exige explicación. La cancelación es definitiva e inmediata.» |
| 8:00–8:40 | Recargar y abrir #N | Cancelado, sin cancelar otra vez | «La nueva consulta conserva CANCELLED.» |
| 8:40–9:30 | Evidencia preparada de automatización | Resultado identificado por commit y clases/spec | «Los errores de propiedad, rol y concurrencia se verifican por pruebas.» |

No lanzar instalaciones, builds ni la suite completa durante estos minutos. Si el checkout demora, usar el pedido Confirmado de reserva y decir que fue creado antes mediante ese mismo recorrido. No sustituir silenciosamente una demo real por una prueba con mocks.

## 10. Qué decir durante la demo

- «Spring Session persiste las sesiones en MySQL; el navegador conserva una cookie HttpOnly.»
- «Los cambios de estado requieren el token CSRF que gestiona el frontend.»
- «El rol activo se selecciona entre los roles asignados a esta cuenta.»
- «A y B tienen cookies independientes, aunque usan la misma cuenta.»
- «A revoca B; el 401 aparece cuando B vuelve a consultar al backend.»
- «A mantiene su sesión después de revocar B.»
- «El logout se confirma en el servidor antes de limpiar la identidad local.»
- «El pedido pertenece al comprador autenticado.»
- «Confirmado es la etiqueta visible de CONFIRMED.»
- «Otro exige una explicación, no solo espacios.»
- «La cancelación pasa directamente a CANCELLED y no requiere una segunda aprobación.»
- «El reembolso mostrado corresponde a la pasarela simulada del backend.»
- «La recarga consulta de nuevo MySQL y conserva el estado Cancelado.»
- «MockMvc y Playwright con respuestas interceptadas no equivalen a un E2E real.»

## 11. Checklist justo antes de presentar

- [ ] Docker activo y `docker compose ps` muestra los tres servicios saludables.
- [ ] Health y readiness del backend devuelven UP.
- [ ] Frontend abre en `http://127.0.0.1:4300/` y el proxy readiness devuelve UP.
- [ ] Credenciales de la cuenta elegida ensayadas; cuenta ACTIVA y correo verificado.
- [ ] Cuenta con COMPRADOR y VENDEDOR; selector probado.
- [ ] Contraseña vigente anotada de forma privada si hubo ensayos de cambio/recuperación.
- [ ] `Camiseta demo local` disponible y se puede agregar.
- [ ] Carrito vacío al iniciar el recorrido de compra.
- [ ] A normal y B incógnito identificadas; otras sesiones de ensayo controladas.
- [ ] Ambas ventanas usan el mismo host y puerto; Network de B preparado para ver el 401.
- [ ] Pedido Confirmado de reserva, propio, con número anotado.
- [ ] No hay otra persona o test modificando el carrito/pedido de esa cuenta.
- [ ] Texto de dirección y explicación listos; no se usará cupón ni pago alternativo.
- [ ] Evidencias automatizadas previamente ejecutadas y su commit identificados.
- [ ] Si se mostrarán flujos de correo, SMTP y buzón comprobados antes de empezar.

## 12. Plan B si algo falla durante la demo

### Diagnóstico común rápido

Desde la raíz:

```bash
docker compose ps
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:4300/api/actuator/health/readiness
docker compose logs --tail=100 backend frontend mysql
```

Si un proceso quedó detenido, volver a levantarlo conservando los datos:

```bash
docker compose up -d --wait
```

Si backend/frontend necesitan reinicio de proceso, sin cambios de configuración:

```bash
docker compose restart backend frontend
```

Repetir readiness antes de continuar. Si se cambió `.env`, usar `docker compose up -d --wait backend frontend` para recrear con el entorno actualizado: `restart` no aplica cambios de variables. No usar `down --volumes` sobre la demo. Un reinicio no revierte compras, cancelaciones, contraseñas ni marcas de verificación.

### CU-08

| Problema | Acción concreta y respaldo |
| --- | --- |
| Login 403 por correo pendiente | Es el bloqueo real de la cuenta local nueva. Usar la cuenta verificada preparada en §4; si no existe y no hay SMTP/buzón, no se puede completar honestamente el flujo manual. Mostrar `EmailVerificationIntegrationTests` y el E2E real aislado de §6 como evidencia distinta. |
| Login incorrecto | Comprobar cuenta/contraseña vigente y que se usa el mismo entorno; el perfil local no restablece credenciales. No insistir con una contraseña anterior a un ensayo. |
| Solo una sesión | B no debe ser otra pestaña normal. Abrir incógnito, hacer login allí y actualizar la lista en A. |
| B parece seguir conectada | Pulsar **Actualizar** en B; observar 401 y limpieza de identidad. No basta con mirar una pantalla que no hizo nuevas peticiones. |
| Se revocó A por error | Entrar otra vez en A, seleccionar COMPRADOR y recrear B. No se perdió la cuenta ni sus pedidos. |
| Logout da error | No afirmar que terminó. Ver logs, recuperar backend y repetir **Cerrar sesión**; comprobar /me 401 al recargar. |

Para volver al inicio: desde A **Cerrar las demás sesiones**, luego **Cerrar sesión**; cerrar todas las ventanas B incógnitas. Volver a abrir A anónima. Si no hay acceso a A, resolver primero backend/login. No limpiar cookies como sustituto de demostrar invalidación del servidor.

Respaldo focalizado, con Java 21/Docker ya preparados:

```bash
(
  cd backend/demo
  ./mvnw -Dtest=SessionAuthenticationTests,PersistentSessionIntegrationTests test
)
```

El E2E real `bash scripts/cu08-real-e2e.sh` es independiente de la cuenta local y SMTP, pero construir su entorno puede exceder el tiempo de demo: tener el informe listo.

### CU-11

| Problema | Acción concreta y respaldo |
| --- | --- |
| No aparece Mis pedidos | Seleccionar COMPRADOR desde **Ver cuenta**. |
| No hay camiseta / está inactiva / sin stock | Comprobar perfil `local` y datos previos. Reiniciar no repone un producto existente. Usar el pedido Confirmado de reserva; no inventar otro producto ni modificar SQL en vivo. |
| No se calcula total | Escribir dirección, elegir Estándar y pulsar **Calcular total**; revisar el error visible y readiness. |
| Pago no aprobado | Comprobar que se eligió **Tarjeta**. Usar el pedido Confirmado de reserva si no hay tiempo para recuperar el checkout. |
| No aparece Cancelar pedido | Comprobar número/estado. Solo Confirmado o En preparación permiten cancelar. Crear un pedido nuevo o usar el de reserva. |
| Confirmación deshabilitada | Elegir motivo; con Otro introducir explicación no vacía, máximo 1000 caracteres. |
| Respuesta perdida / timeout al cancelar | Volver a cargar el detalle antes de repetir. Si está Cancelado, la operación pudo completarse aunque se perdiera la respuesta. |
| Reembolso pendiente | Mostrar el mensaje real y Cancelado; no prometer que ya está completado. La prueba `pendingRefundKeepsTheOrderCancelledAndDoesNotRestoreStockTwice` acredita la separación de estados. |
| Desapareció el mensaje financiero al recargar | Es esperado: el detalle recargado solo permite comprobar estado y datos del pedido, no el mensaje temporal del POST. |

Para repetir: abrir carrito y eliminar únicamente restos del ensayo; crear un nuevo pedido siguiendo 18–22. Conservar el cancelado como evidencia. No hay botón de “deshacer cancelación”. Mostrar `OrderOwnershipIntegrationTests` o su reporte si el flujo manual falla; `node --test tests/mis-pedidos.test.cjs` desde `frontend` sirve como evidencia de lógica frontend, no sustituye la persistencia real.

## 13. Archivos relacionados

Rutas relativas a Transformers-AS. En las tablas Java, **M** = `backend/demo/src/main/java/com/transformersas/marketplace/`; **T** = `backend/demo/src/test/java/com/transformersas/marketplace/`. Concatenar prefijo y ruta de la fila; no son paquetes nuevos.

| Implementación | Ruta real |
| --- | --- |
| Seguridad, login/logout, CSRF y permisos | M + `shared/SecurityConfiguration.java` |
| Cuenta, rol, sesiones y contraseña | M + `auth/infrastructure/web/controller/SessionController.java` |
| Verificación / recuperación HTTP | M + `auth/infrastructure/web/controller/EmailVerificationController.java`; `auth/infrastructure/web/controller/PasswordRecoveryController.java` |
| Sesiones y contraseñas | M + `auth/application/usecase/ManageAccountSessions.java`; `auth/application/usecase/ChangeAccountPassword.java`; `auth/application/usecase/RecoverAccountPassword.java`; `auth/application/usecase/VerifyAccountEmail.java` |
| Cookie/duración y cuenta vigente | M + `auth/infrastructure/security/LoginSessionPolicy.java`; `auth/infrastructure/security/CurrentAccountSessionFilter.java` |
| Aprovisionamiento local | M + `users/infrastructure/config/LocalDemoAccountConfiguration.java`; `product/LocalDemoProductConfiguration.java` |
| Registro alternativo | M + `sellers/SellerRegistrationService.java` |
| Consulta/cancelación HTTP | M + `orders/infrastructure/web/controller/OrderController.java` |
| Propiedad y cancelación | M + `orders/application/usecase/FindOwnOrders.java`; `orders/application/usecase/RequestOrderCancellation.java`; `orders/application/usecase/CancelOrderUseCase.java` |
| Creación y preparación | M + `orders/application/usecase/CreateOrderUseCase.java`; `orders/application/usecase/StartPreparationUseCase.java`; `orders/infrastructure/web/controller/SellerOrderFulfillmentController.java` |
| Reembolso y adaptadores de pago | M + `payments/application/usecase/RequestRefundUseCase.java`; `payments/infrastructure/gateway/MockPaymentGateway.java`; `payments/infrastructure/gateway/SimulatedRefundGateway.java` |
| Persistencia de pedidos | M + `orders/infrastructure/persistence/repository/OrderRepositoryAdapter.java`; `orders/infrastructure/persistence/repository/JdbcOrderCancellationRepository.java` |
| Pruebas principales CU-08 | T + `auth/SessionAuthenticationTests.java`; `auth/PersistentSessionIntegrationTests.java`; `auth/CurrentAccountSessionIntegrationTests.java`; `auth/EmailVerificationIntegrationTests.java`; `auth/PasswordRecoverySmtpIntegrationTests.java` (resto en §6) |
| Pruebas principales CU-11 | T + `orders/OrderOwnershipIntegrationTests.java`; `orders/CheckoutOrderCreationTests.java`; `orders/SellerStartPreparationTests.java`; `payments/RequestRefundUseCaseTests.java`; `payments/RefundOrderTotalCapTests.java` |

| Frontend / ejecución / documentación | Ruta real |
| --- | --- |
| Panel de cuenta y lógica | `frontend/src/app/core/components/acceso.component.html`; `frontend/src/app/core/components/acceso.component.ts` |
| Sesión e interceptor | `frontend/src/app/core/services/auth.service.ts`; `frontend/src/app/core/interceptors/session.interceptor.ts` |
| Identidad visible | `frontend/src/app/cuenta/components/perfil-resumen.component.ts` |
| Catálogo, carrito y checkout | `frontend/src/app/app.component.html`; `frontend/src/app/app.component.ts` |
| Lista/detalle/cancelación | `frontend/src/app/pedidos/components/mis-pedidos.component.html`; `frontend/src/app/pedidos/components/mis-pedidos.component.ts`; `frontend/src/app/pedidos/components/resumen-pedido.component.ts` |
| Contrato HTTP y etiquetas | `frontend/src/app/pedidos/services/pedidos.service.ts`; `frontend/src/app/pedidos/models/pedido.model.ts` |
| Preparación vendedor / registro | `frontend/src/app/panel-vendedor/components/pedidos-recibidos.component.ts`; `frontend/src/app/registro-vendedor/components/registro-vendedor.component.ts` |
| Prueba unitaria CU-11 | `frontend/tests/mis-pedidos.test.cjs` |
| Playwright con mocks CU-08 | `frontend/e2e/email-verification.spec.ts`; `frontend/e2e/persistent-session.spec.ts`; `frontend/e2e/logout.spec.ts`; `frontend/e2e/revoke-other-sessions.spec.ts`; `frontend/e2e/remaining-access-blockers.spec.ts` |
| E2E real CU-08 | `frontend/e2e-real/account-session.spec.ts`; `frontend/playwright.real.config.ts`; `scripts/cu08-real-e2e.sh`; `scripts/cu08-real-seed.sql`; `compose.e2e.yaml` |
| Configuración frontend/pruebas | `frontend/package.json`; `frontend/playwright.config.ts`; `frontend/nginx.conf`; `frontend/Dockerfile` |
| Arranque | `compose.yaml`; `.env.example`; `backend/demo/Dockerfile`; `backend/demo/src/main/resources/application.properties`; `backend/demo/pom.xml` |
| Alternativa Swarm | `stack.yml`; `deploy.sh`; `docs/despliegue/docker-swarm.md` |
| CI | `.github/workflows/backend-ci.yml` |
| Documentación CU-08 | `docs/pruebas/cu-08-autenticacion-y-sesiones.md`; `docs/seguridad/mantener-sesion-iniciada.md`; `docs/seguridad/permisos-vigentes-en-sesion.md`; `docs/seguridad/verificacion-correo.md`; `docs/seguridad/recuperacion-contrasena-smtp.md`; `docs/seguridad/aislamiento-cuentas-compradores.md` |
| Referencia externa CU-11 | `../Transformers-Integration-Tests/tests/e2e/checkout-order-cancellation.spec.ts`; `../Transformers-Integration-Tests/tests/helpers/account.ts`; `../Transformers-Integration-Tests/playwright.config.ts` |

Migraciones relevantes, todas bajo `backend/demo/src/main/resources/db/migration/`:

- `V5__add_orders.sql`: pedidos e ítems.
- `V6__create_user_accounts.sql`: cuentas y roles.
- `V7__create_jdbc_sessions.sql`: sesiones JDBC.
- `V10__create_password_recovery_tokens.sql`: recuperación.
- `V11__add_order_account.sql`: propietario del pedido.
- `V12__expand_order_status.sql`: estados de pedido.
- `V13__add_stores_and_order_delivery_snapshot.sql`: tienda y datos de entrega.
- `V14__add_order_status_history.sql`: historial.
- `V19__add_order_cancellations_and_refunds.sql`: cancelaciones, reembolsos y estado financiero.
- `V28__create_seller_registration.sql`: registro de vendedor, columna `email_verified_at` y verificación de las cuentas que ya existían al aplicar esa migración.
- `V31__create_email_verification_tokens.sql`: tokens y cierre de sesiones de cuentas no verificadas.
- `V32__isolate_buyer_resources.sql`: propiedad de recursos del comprador.
