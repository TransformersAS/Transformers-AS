# Marketplace Backend — TransformersAS

Foundation del monolito modular: Java 21, Spring Boot 4.1.1, MySQL **8.4.11 LTS**,
Flyway y Hibernate en `validate`. Todavía no hay casos de uso ni esquema de negocio.
La carpeta física sigue siendo `backend/demo` para conservar rutas de Docker y CI;
la identidad Maven es `com.transformersas:marketplace-backend` y el paquete base es
`com.transformersas.marketplace`, con `MarketplaceApplication` como clase principal.

## Requisitos y arranque local

- JDK **21**, seleccionado en `JAVA_HOME`.
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

Levantar MySQL y backend:

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
`backend/demo/target/surefire-reports/`. No hay un umbral obligatorio de cobertura.

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
| `auth` | Autenticación y autorización pendientes |
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

## Seguridad provisional: NO es la seguridad final

`shared/DevelopmentSecurityConfiguration` aplica en el arranque actual, incluyendo
las imágenes de demo. Permite sin autenticación `/actuator/health`, sus subrutas y
**`/api/**` para desarrollo temporal**. No crea endpoints; el resto de rutas queda
denegado. Permite el despacho interno de errores para preservar respuestas HTTP.

No existen usuarios, credenciales predeterminadas, login, JWT ni autorización de
negocio. Se excluye `UserDetailsServiceAutoConfiguration` para no generar el
usuario/contraseña del template. Form login, HTTP Basic y logout están desactivados.
Las rutas `/api/**` están temporalmente exentas de CSRF para permitir desarrollo
de operaciones de escritura anónimas; esto debe revisarse con la autenticación real.

**No usar esta política con datos reales o exposición pública.** Antes del CU de
autenticación, acordar JWT o sesiones (y su almacenamiento), permisos, CSRF/CORS y
pruebas; reemplazar la política abierta y revisar la exclusión de autoconfiguración.
No se seleccionó JWT ni sesiones JDBC por añadir este starter.

## Capacidades disponibles, todavía sin políticas de negocio

- **Resilience4j:** `resilience4j-spring-boot4:2.4.0`, específico para Boot 4, más
  `spring-boot-starter-aspectj` para soporte AOP. Versión explícita porque el módulo
  Boot 4 no está incluido en el BOM 2.4.0. No existen instancias de circuit breaker,
  reintentos, límites ni integraciones externas configuradas. La validación actual
  comprueba arranque; cada integración necesitará pruebas de su política real.
- **Spring Cache + Caffeine:** infraestructura habilitada con `@EnableCaching` y
  `spring.cache.type=caffeine`. No hay `@Cacheable`, cachés ni TTL configurados.
  Antes de usarla definir límites de tamaño, expiración e invalidación a partir de
  mediciones. Caffeine es local a cada réplica; no es una caché distribuida.
- **WireMock:** `wiremock-standalone:3.13.2` estable, exclusivamente `test`. Se usa
  la distribución con dependencias aisladas para evitar conflictos de Jetty/Jackson
  con Boot 4. No se arranca ningún servidor ni se crean stubs todavía.

Referencias: [Resilience4j Boot 4 y BOM](https://github.com/resilience4j/resilience4j/issues/2427),
[WireMock](https://wiremock.org/docs/download-and-installation/),
[Spring Security en Boot](https://docs.spring.io/spring-boot/reference/web/spring-security.html).

## Reglas del equipo

- No trabajar directamente en `main`; usar una rama por cambio.
- Antes de integrar una rama, traer `main` y resolver conflictos:
  `git fetch origin`, `git merge origin/main`, y luego `./mvnw clean verify` en el backend.
- Cada cambio de esquema va en una migración Flyway en
  `backend/demo/src/main/resources/db/migration/`.
- No usar `ddl-auto=update`, `create` ni `create-drop`; Hibernate solo valida.
- No modificar migraciones aplicadas: añadir una versión nueva.
- V1 queda pendiente hasta definir el primer esquema compartido; no añadir SQL vacío.
- No hardcodear secrets. Compose usa `.env`; Swarm usa secrets/configtree.
- Cada CU debe vivir en su módulo de negocio, con pruebas y contratos acordados.
- No introducir políticas de resiliencia, caché o stubs sin una necesidad concreta.

## Infraestructura

Compose es para desarrollo local. Swarm es para el despliegue distribuido/demo:
ver [docs/swarm.md](docs/swarm.md). MySQL permanece en una sola réplica y **no tiene HA**.

La [CI](.github/workflows/backend-ci.yml) ejecuta Maven y construye la imagen en
push a ramas; solo `main` publica en GHCR con tags `latest` y `sha-<commit>` para
amd64/arm64. El cambio de artifactId no cambia las rutas ni el nombre del paquete
GHCR: el Dockerfile copia el JAR por patrón. No se añade CD remoto.

Esta foundation no acredita disponibilidad ni rendimiento cuantitativos. Quedan
pendientes las políticas de seguridad definitivas, contratos y esquema de cada CU,
integraciones reales y validación física multi-nodo cuando exista infraestructura.
