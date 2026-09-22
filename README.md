# Marketplace — TransformersAS

La documentación está organizada por tema en el [índice de documentación](docs/README.md).

Aplicación con frontend Angular/Ionic, backend Java 21 con Spring Boot y MySQL
**8.4.11 LTS**. Docker Compose permite ejecutar el sistema completo; Flyway gestiona
el esquema y Hibernate lo valida. El backend está en `backend/demo`.

## Instalación de la demo en Windows (profesor)

### Requisitos

- **Docker Desktop instalado y abierto**, configurado para **Linux containers**.
- Internet para descargar las imágenes y dependencias en la primera ejecución.
- Puertos **4300, 8080 y 3307** libres.

El ejecutable incluye .NET. No necesita instalar Java, Maven, Node, Git ni .NET,
abrir una terminal o configurar contraseñas manualmente.

### Descargar e iniciar

1. Descargar la **entrega completa `Marketplace-demo.zip`** proporcionada por el equipo.
2. Descomprimirla en una carpeta con permisos de escritura. No ejecutar desde dentro del ZIP.
3. Comprobar que `Marketplace.exe` esté junto a los archivos y carpetas siguientes:

   ```text
   Transformers-AS/
   ├── Marketplace.exe
   ├── Cerrar Marketplace.cmd
   ├── compose.yaml
   ├── compose.demo.yaml
   ├── frontend/
   ├── backend/demo/
   └── docs/
   ```

4. Abrir Docker Desktop y esperar a que el motor esté listo.
5. Hacer doble clic en **Marketplace.exe**.
6. Esperar mientras se construyen e inician MySQL, backend y frontend. La primera
   ejecución puede tardar varios minutos.
7. Cuando aparezca **«Marketplace está listo»**, el navegador se abrirá en
   **http://localhost:4300**. Si no se abre automáticamente, entrar a esa dirección.

El launcher genera la configuración y las contraseñas locales en `.env.demo`;
crea también `.env` si no existe y conserva uno preexistente. Prepara automáticamente
las cuentas y el pedido del perfil `demo` en un volumen propio de MySQL.
**No elimine `.env.demo` después del primer inicio**, porque conserva las contraseñas
con las que se creó esa base de datos.

### Cuentas y pedido listos para la demostración

| Caso | Correo | Contraseña | Datos preparados |
|---|---|---|---|
| CU-08 | `demo@marketplace.local` | `MarketplaceDemo123!` | Cuenta activa y verificada; roles COMPRADOR y VENDEDOR |
| CU-11 | `comprador.demo@example.com` | `MarketplaceDemo123!` | Cuenta activa y verificada; rol COMPRADOR y un pedido Confirmado |

Para CU-08, abrir **Ver cuenta**, iniciar sesión, cambiar el rol activo, consultar
**Sesiones activas** y pulsar **Cerrar sesión**.

Para CU-11, iniciar sesión con el comprador y abrir **Mis pedidos → Ver detalle →
Cancelar pedido → Otro**. Escribir **Cancelación de demostración CU-11.** y confirmar.
Al recargar, el pedido seguirá Cancelado y ya no ofrecerá la opción de cancelación.
Pago y reembolso usan el simulador existente del proyecto; autenticación, sesiones,
CSRF, inventario y persistencia en MySQL usan la implementación real.

### Cerrar, volver a abrir y resetear

- **Detener el sistema:** hacer doble clic en `Cerrar Marketplace.cmd`. Los datos se conservan.
- **Cerrar solo la ventana del launcher:** los contenedores siguen ejecutándose.
- **Volver a abrir o repetir la demo:** cerrar la ventana anterior y abrir otra vez
  `Marketplace.exe`, con Docker Desktop activo. El mismo pedido demo cancelado vuelve
  a Confirmado; no se duplican usuarios ni pedidos y los demás pedidos se conservan.
- Si el navegador conserva una sesión anterior, pulsar **Cerrar sesión** antes de
  empezar el guion con la cuenta CU-08.

Si ocurre un error, la ventana permanece abierta. Comprobar Docker Desktop, Internet,
los puertos indicados y que la entrega esté completamente descomprimida. El diagnóstico
queda en `launcher-logs/marketplace.log`. El ejecutable no está firmado y Windows puede
mostrar SmartScreen; verificar que la entrega proceda del equipo del proyecto.

### Si se descarga el código desde GitHub

El repositorio incluye **`Marketplace.exe` en la raíz**. Descargar **Code → Download ZIP**,
descomprimir el proyecto completo, abrir Docker Desktop y hacer doble clic en el ejecutable.
No descargar únicamente el `.exe`: necesita `compose.yaml`, `compose.demo.yaml`, `frontend/`
y `backend/demo/` en la misma entrega.

Para regenerar el ejecutable, un integrante del equipo puede ejecutar
**Actions → Build Marketplace launcher → Run workflow**. Descargar el artifact
**`Marketplace-win-x64`**, descomprimirlo y reemplazar `Marketplace.exe` en la raíz.
El artifact contiene solo el ejecutable; necesita las carpetas del proyecto.

También se puede construir desde la raíz del repositorio con **SDK .NET 8** instalado
(solo en el equipo que prepara la entrega):

```text
dotnet publish launcher/Marketplace/Marketplace.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o .
```

El resultado es `Marketplace.exe` en la raíz, para Windows x64, con el runtime incluido.
Para distribuirlo, empaquetar el ejecutable y el proyecto completo, conservando los
archivos ocultos de construcción, pero excluyendo `.env`, `.env.demo`, `.git`,
`node_modules`, `target` y los registros de desarrollo.

Más información: [guía breve del profesor](docs/ejecucion/iniciar-demo-windows.md) y
[construcción, configuración y pruebas del launcher](docs/ejecucion/construir-ejecutable-windows.md).
Este arranque usa Compose; `deploy.sh` y `stack.yml` corresponden al escenario separado
[de Docker Swarm](docs/despliegue/docker-swarm.md).

## Arranque manual para desarrollo (alternativa al ejecutable)

- JDK **21**, seleccionado en `JAVA_HOME`, para ejecutar el backend o sus pruebas desde el host.
  No es necesario si todo se ejecuta con Docker Compose.
- Docker con Compose v2; Docker Desktop activo en macOS.
- No hace falta instalar Maven: usar el Wrapper del repositorio.

Desde la raíz, la primera vez:

```bash
cp .env.example .env
```

Editar `.env` y reemplazar las contraseñas de aplicación y root por valores locales
distintos. No versionar este archivo ni sobrescribir uno existente. Mantener:

```dotenv
DB_HOST=localhost
DB_PORT=3307
MYSQL_HOST_PORT=3307
BACKEND_HOST_PORT=8080
```

Levantar frontend, backend y MySQL:

```bash
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
BACKEND_ADDRESS="$(docker compose port backend 8080)"
curl --fail "http://${BACKEND_ADDRESS}/actuator/health"
curl --fail "http://${BACKEND_ADDRESS}/actuator/health/liveness"
curl --fail "http://${BACKEND_ADDRESS}/actuator/health/readiness"
```

El resultado esperado es `UP`. Compose conecta el backend a `mysql:3306`; una
ejecución desde el host usa `localhost:3307`. No se usa el MySQL instalado en el Mac.
El healthcheck Docker usa liveness; readiness incluye la base de datos.

Ejecutar las pruebas (Docker debe seguir activo; no requieren `.env` ni Compose):

```bash
# Solo macOS: seleccionar el JDK instalado.
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"

cd backend/demo
./mvnw clean verify
```

Testcontainers arranca su propio MySQL 8.4.11 temporal. JaCoCo genera
`backend/demo/target/site/jacoco/index.html`; Surefire guarda sus reportes en
`backend/demo/target/surefire-reports/`. La configuración normal exige un mínimo
de 95 % de cobertura de líneas. La suite exclusiva de integración se documenta en
[guía de cobertura de integración](docs/pruebas/cobertura-integracion-backend.md).

Para ejecutar Spring Boot desde el host, detener el backend Compose para liberar
8080 y exportar `.env` en la terminal de desarrollo:

```bash
# Desde la raíz.
docker compose stop backend
docker compose up -d --wait mysql
set -a
source .env
set +a
cd backend/demo
./mvnw spring-boot:run
```

MySQL aplica las credenciales de inicialización solo con un volumen vacío.
Cambiar `.env` no cambia la contraseña de una base existente; coordinar cualquier
rotación. No borrar volúmenes para resolver problemas sin revisar sus datos.

## Organización modular

Cada módulo tiene un `package-info.java` que describe su responsabilidad:

| Paquete | Responsabilidad |
| --- | --- |
| `auth` | Autenticación, roles activos y sesiones |
| `users` | Usuarios, perfiles y cuentas |
| `catalog` | Catálogo comercial |
| `inventory` | Existencias, disponibilidad y reservas |
| `orders` | Ciclo de vida de pedidos |
| `payments` | Pagos y proveedores |
| `logistics` | Envíos y entregas |
| `reviews` | Reseñas y valoraciones |
| `returns` | Devoluciones |
| `notifications` | Notificaciones y canales |
| `shared` | Configuración técnica y contratos transversales |

Los paquetes establecen organización, no aislamiento automático. No se añadió
Spring Modulith. Evitar colocar lógica de un módulo de negocio en `shared`.

## Autenticación y datos demo

La aplicación utiliza autenticación real, Spring Session con persistencia JDBC,
selección de rol activo y CSRF. Las cuentas y el pedido de la entrega Windows se
preparan exclusivamente al activar el perfil `demo`; no se crean en el arranque
normal de producción. El perfil `local` tiene su propia preparación de desarrollo
y no equivale al fixture de la entrega Windows.

## Reglas del equipo

- No trabajar directamente en `main`; usar una rama por cambio.
- Antes de integrar una rama, traer `main` y resolver conflictos:
  `git fetch origin`, `git merge origin/main`, y luego `./mvnw clean verify` en el backend.
- Cada cambio de esquema va en una migración Flyway en
  `backend/demo/src/main/resources/db/migration/`.
- No usar `ddl-auto=update`, `create` ni `create-drop`; Hibernate solo valida.
- No modificar migraciones aplicadas: añadir una versión nueva.
- No hardcodear secrets. Compose usa `.env`; Swarm usa secrets/configtree.
- Cada CU debe vivir en su módulo de negocio, con pruebas y contratos acordados.
- No introducir políticas de resiliencia, caché o stubs sin una necesidad concreta.

## Infraestructura

Compose es para desarrollo local y para la entrega Windows. Swarm es para el despliegue distribuido:
ver [guía de Docker Swarm](docs/despliegue/docker-swarm.md). `./deploy.sh --local` construye y despliega
frontend, backend y MySQL desde este repositorio, y ejecuta su smoke test de
health/readiness antes de terminar.
MySQL permanece en una sola réplica y **no tiene HA**.

La [CI](.github/workflows/backend-ci.yml) ejecuta Maven y construye las imágenes
backend y frontend en push a ramas; solo `main` publica ambas en GHCR con tags
`latest` y `sha-<commit>` para amd64/arm64. El cambio de artifactId no cambia las
rutas ni el nombre del paquete GHCR: el Dockerfile copia el JAR por patrón. No se
añade CD remoto.

La compilación del ejecutable Windows y las pruebas del flujo funcional se describen
en [construcción del ejecutable de Windows](docs/ejecucion/construir-ejecutable-windows.md), incluida la validación manual pendiente
sobre Windows. Las pruebas locales no acreditan disponibilidad multi-nodo.
