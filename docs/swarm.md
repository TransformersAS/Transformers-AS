# Baseline Docker Swarm

Esta configuración es independiente de `compose.yaml`. No incluye CD remoto ni
runner self-hosted. Ejecutar `deploy.sh` desde un manager con Docker y curl. Un
solo comando despliega frontend, backend y MySQL; espera sus probes antes de
terminar.

**Si solo quieres levantarlo, ve directo a [Paso a paso del despliegue local](#paso-a-paso-del-despliegue-local).**
Lo de arriba es el porqué; eso es el cómo.

**En Windows, desplegar con Docker Desktop, no con el Docker de WSL.** Un stack desplegado en el motor
de WSL funciona dentro de WSL pero queda inalcanzable desde el navegador de Windows (comprobado: fallan
`localhost`, `127.0.0.1` y `[::1]`).

Compose y Swarm publican el backend en el puerto 8080 por defecto: no ejecutar
ambos con ese puerto en el mismo host. Antes de probar Swarm, detener el backend
de Compose (`docker compose stop backend`) o recrearlo con otro
`BACKEND_HOST_PORT`. Comprobar `docker ps` además de `docker service ls`: en Docker
Desktop un contenedor Compose puede recibir las peticiones a `127.0.0.1:8080`
aunque Swarm muestre sus dos réplicas disponibles. Si ocurrió la colisión,
volver a publicar el puerto del servicio Swarm después de liberar el de Compose.

El frontend publica el puerto 80 (18000 con `--local`) y reenvía `/api` a las
réplicas backend. Las pruebas k6 se dirigen normalmente al frontend, por ejemplo
`BASE_URL=http://localhost:18000 k6 run scripts/k6/catalog-browse.js`.

## Arquitectura y límites

- `frontend`: imagen privada de GHCR, dos réplicas Nginx, healthcheck `/healthz`
  y proxy de `/api` al backend.
- `backend`: imagen privada de GHCR, dos réplicas, healthcheck **liveness** heredado
  de la imagen, restart con 10 s de espera y actualización de una réplica a la vez.
  `start-first` requiere capacidad temporal para una tercera réplica. Un fallo
  detectado durante la actualización solicita rollback de la aplicación.
- `mysql`: MySQL 8.4.11 LTS, una réplica, volumen local `<stack>_mysql_data`, fijado al ID
  de un manager concreto. Las actualizaciones usan `stop-first` para evitar dos
  escritores simultáneos sobre ese volumen. **No hay HA ni replicación de MySQL.**
- Los tres servicios comparten una red overlay `<stack>_internal`. El backend usa
  `mysql:3306` por DNS de Swarm; MySQL no publica puertos al host.
- El routing mesh publica el puerto del backend en los nodos del Swarm y dirige
  las conexiones a sus tasks, incluso si el nodo receptor no tiene una réplica.
  A diferencia de Compose de desarrollo, **no queda restringido a 127.0.0.1**.
  Restringir acceso mediante la red/firewall del entorno. No se añade TLS aquí.

Compose administra contenedores en un host. Un stack declara servicios cuyo
estado deseado Swarm mantiene: si una task falla, crea otra. Dos réplicas en un
solo computador permiten probar reconciliación, pero comparten CPU, memoria,
daemon y máquina. No demuestran tolerancia a la caída de un computador.

El volumen MySQL no viaja entre nodos. No cambiar `MYSQL_NODE_ID` sin un plan de
backup/restauración: en otro nodo aparecería un volumen local distinto, vacío.
La caída del manager de MySQL interrumpe las operaciones con base de datos. Un
solo manager también es un punto único de fallo del control del cluster.

Swarm no espera un `depends_on` saludable. El backend puede fallar durante la
inicialización de MySQL; su restart policy reintenta. `deploy.sh` espera hasta
600 s, exige réplicas Running y tres respuestas readiness UP consecutivas.
Esto no sustituye revisar el healthcheck de cada task en su nodo.

Liveness comprueba el estado del proceso; readiness incluye `readinessState,db`.
El routing mesh no consulta readiness: una caída de MySQL puede dejar los
backends vivos y enrutables pero no preparados para operar. Falta una política
de tráfico que use readiness para un entorno con requisitos de disponibilidad.
Un rollback de imagen tampoco revierte cambios de esquema hechos por Flyway.

## Secrets sin contraseñas en el servicio

Se crean tres secrets externos al stack, por defecto:

- `<stack>_db_password_v1`
- `<stack>_mysql_root_password_v1`
- `<stack>_logistics_webhook_secret_v1`

MySQL usa `MYSQL_PASSWORD_FILE` y `MYSQL_ROOT_PASSWORD_FILE`. La imagen oficial
lee esos archivos al inicializar una base vacía. El backend monta solo la clave
de aplicación como `/run/secrets/DB_PASSWORD`, legible por UID/GID 10001, modo
0400, y usa `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`. Spring Boot incorpora
el nombre del archivo como propiedad `DB_PASSWORD`, resolviendo el placeholder
actual sin cambiar el código ni exportar la contraseña como variable de entorno.

Por el mismo mecanismo, el secreto del webhook logístico se monta como
`/run/secrets/logistics.webhook.secret`: el nombre del archivo **es** la propiedad que lee el backend,
así que sustituye el valor vacío de `application.properties` y el webhook de CU-24 y CU-25 queda
abierto. Si no se entrega un archivo, `deploy.sh` genera un valor aleatorio de 48 caracteres, porque es
un secreto que nadie escribe a mano; para poder firmar novedades hay que crearlo antes con un valor
conocido (paso 6 del despliegue).

`docker service inspect` muestra rutas y referencias, no valores de contraseñas.
El administrador del daemon sigue teniendo capacidad para acceder a secrets:
no es una barrera frente a administradores de Docker.

El script solicita las dos contraseñas sin eco si los secrets no existen. Para
ejecución no interactiva, proporcionar `DB_PASSWORD_SECRET_FILE` y
`MYSQL_ROOT_PASSWORD_SECRET_FILE`, con rutas **fuera del repositorio**, permisos
0600 y contenido sin salto de línea final. No pasar contraseñas como argumentos.
No carga `.env`. Reutiliza secrets existentes sin reemplazarlos.

Los secrets son inmutables. Su rotación requiere coordinar el cambio real de
contraseña en MySQL y nuevos nombres de secret; recrear un secret no cambia las
credenciales de un volumen ya inicializado. Conservar copias en un gestor seguro.

Referencias oficiales:
[secrets Docker](https://docs.docker.com/engine/swarm/secrets/),
[entrypoint MySQL 8.4](https://github.com/docker-library/mysql/blob/master/8.4/docker-entrypoint.sh),
[configtree Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## Dueña de la tienda principal (`MAIN_STORE_OWNER_EMAIL`)

Desde CU-18 solo la cuenta dueña de una tienda puede operarla. La "Tienda principal" (id 1) es anterior a ese caso de
uso y la migración la deja **sin dueña**, porque no puede inventar una cuenta. En Swarm, donde no se usa el perfil
`local` (que asigna la tienda a la cuenta demo), esto significa que ningún vendedor puede gestionar los pedidos de la
tienda 1 —ni los productos anteriores, que pertenecen a ella— hasta que alguien la reciba: sin `X-Store-Id` responde
401 `STORE_IDENTITY_MISSING` y con `X-Store-Id: 1`, 403 `STORE_NOT_AUTHORIZED`.

Para asignarla sin editar datos a mano, definir la variable **antes** de desplegar:

```bash
MAIN_STORE_OWNER_EMAIL=vendedor@empresa.com \
STACK_NAME=transformers \
./deploy.sh
```

`deploy.sh` no limpia el entorno, así que `docker stack deploy` sustituye `${MAIN_STORE_OWNER_EMAIL:-}` del `stack.yml`
con lo que exportó quien despliega. No es un secreto (es un correo), por eso va como variable y no como secret.

Qué hace el backend al arrancar (cada réplica, en cada arranque):

- **Vacía o sin definir (el valor por defecto):** no hace nada. Un despliegue existente no cambia hasta que se defina.
- **Solo escribe una columna de una fila:** `stores.owner_account_id` de la tienda 1, y solo si está vacía. No
  reemplaza a una dueña existente, no toca pedidos, productos ni otras tiendas, y no borra ni reescribe nada.
- **Requisitos de la cuenta:** debe existir y tener el rol `VENDEDOR`, y no ser ya dueña de otra tienda (una cuenta
  solo puede serlo de una). La cuenta debe crearse antes; el siguiente arranque o despliegue la asigna.
- **Idempotente y seguro con 2 réplicas:** la asignación es un `UPDATE ... WHERE owner_account_id IS NULL`. Si ambas
  arrancan a la vez, una gana y la otra registra que la tienda ya tiene dueña.
- **Nunca impide el arranque.** Si el correo no corresponde a una cuenta, la cuenta no es vendedora, ya es dueña de otra
  tienda o falla la base de datos, se registra una advertencia y el backend sigue arrancando. El correo no se escribe
  en el log.

Para comprobarlo, buscar en los logs (`docker service logs <stack>_backend`) alguno de estos mensajes: "La tienda
principal quedó asignada…", "La tienda principal ya tiene dueña…" o la advertencia que explica por qué no se asignó.

No definir esta variable junto con el perfil `local`: ese perfil ya asigna la tienda a la cuenta demo, y el runner
encontraría la tienda con dueña y no cambiaría nada.

Si la tienda 1 solo es la semilla de demostración y no tiene pedidos reales, puede dejarse sin dueña.

## Imagen privada y versión

Autenticarse antes de desplegar, sin guardar tokens en el repositorio:

```bash
docker login ghcr.io
```

Usar una credencial con acceso de lectura al paquete privado (por ejemplo, token
de GitHub con `read:packages`, y autorización SSO si la organización la requiere).
Introducirla en el prompt, no en la línea de comandos ni en archivos versionados.
Docker Desktop puede conservarla en su almacén de credenciales.

`deploy.sh` verifica la descarga y usa `docker stack deploy --with-registry-auth
--resolve-image always`, enviando la autenticación a los agentes de Swarm. Cada
nodo necesita conectividad a GHCR. No se construye ninguna imagen desde el stack.

`BACKEND_IMAGE_TAG=latest` y `FRONTEND_IMAGE_TAG=latest` sirven para pruebas
iniciales. Si no se define `FRONTEND_IMAGE_TAG`, el script usa el tag del backend.
Preferir el mismo `sha-<SHA completo publicado>` para identificar una versión
coherente de ambas imágenes.
Swarm resuelve el digest del registro. Las etiquetas pueden cambiar. MySQL está fijado a `8.4.11`;
fijar una versión no equivale a fijar todo por digest.

Verificar arquitecturas antes de desplegar:

```bash
docker info --format '{{.Architecture}}'
docker manifest inspect ghcr.io/transformersas/transformers-as-backend:latest
docker manifest inspect ghcr.io/transformersas/transformers-as-frontend:latest
```

La CI publica para amd64 y arm64. Verificar el manifiesto de la etiqueta elegida:
las imágenes anteriores al arreglo multi-arquitectura pueden contener solo amd64.
Si falta la arquitectura del nodo, usar una versión publicada compatible; no se
asume emulación en Swarm ni se sustituye GHCR por una imagen local.

## Validación estática

Desde la raíz:

```bash
MYSQL_NODE_ID=validation-node \
DB_PASSWORD_SECRET=validation-app \
MYSQL_ROOT_PASSWORD_SECRET=validation-root \
docker stack config -c stack.yml

bash -n deploy.sh
docker run --rm -v "$PWD:/mnt:ro" koalaman/shellcheck:v0.11.0 deploy.sh
git diff --check
```

Los valores de validación son nombres/IDs ficticios, no contraseñas ni recursos.

## Paso a paso del despliegue local

Recorrido completo, en orden, tal como se ejecutó y se comprobó. Deja el Marketplace entero
—frontend, backend con dos réplicas y MySQL— corriendo en un Swarm de un solo nodo.

### Antes de empezar: dónde se ejecuta

**Docker Desktop en Windows, desde Git Bash.** No desde el Docker de WSL.

Desplegar el stack en el motor Docker de WSL sí funciona *dentro* de WSL, pero el resultado queda
**inalcanzable desde Windows**: el navegador y `curl` no llegan a los puertos publicados, ni por
`localhost`, ni por `127.0.0.1`, ni por `[::1]`. El reenvío de puertos de WSL no se lleva bien con el
enrutamiento interno de Swarm. Si ya lo desplegaste ahí, retíralo (`docker stack rm <nombre>` dentro
de WSL) antes de repetirlo en Docker Desktop, o los puertos chocarán.

Comprobar dónde estás parado:

```bash
docker context show     # debe ser desktop-linux
docker info --format '{{.Swarm.LocalNodeState}}'
```

### Paso 1. Archivos de contraseña

El script pide las contraseñas por teclado si no existen los secrets. Para no depender de eso,
prepararlas en archivos **fuera del repositorio**, de una sola línea y sin salto final:

```bash
mkdir -p ~/swarm-secrets
printf 'UnaClaveLocalApp' > ~/swarm-secrets/db.txt
printf 'OtraClaveLocalRoot' > ~/swarm-secrets/root.txt
chmod 600 ~/swarm-secrets/*.txt
wc -l ~/swarm-secrets/*.txt      # debe decir 0 líneas en ambos
```

### Paso 2. Desplegar

```bash
export STACK_NAME=transformers-local
export DB_PASSWORD_SECRET_FILE=~/swarm-secrets/db.txt
export MYSQL_ROOT_PASSWORD_SECRET_FILE=~/swarm-secrets/root.txt
./deploy.sh --local
```

Un solo comando hace todo: construye las dos imágenes con los mismos Dockerfile que publica la CI,
inicializa el Swarm si hace falta, crea los tres secrets, despliega el stack y **espera** a que las
réplicas converjan y a que respondan tres comprobaciones seguidas de salud antes de terminar.

La primera vez tarda bastante, porque compila el backend (unos 600 archivos) y el frontend dentro de
las imágenes. Las siguientes reutilizan la caché.

Termina con las tres líneas de servicios y el mensaje «Frontend y API disponibles». Si no converge en
el plazo, **no borra nada**: deja el stack en pie para que se pueda diagnosticar.

### Paso 3. Comprobar que responde

```bash
docker stack services transformers-local
curl --fail http://localhost:18000/healthz
curl --fail http://localhost:18000/api/actuator/health/readiness
curl --fail http://localhost:18090/actuator/health/readiness
```

Esperado: `mysql 1/1`, `frontend 2/2`, `backend 2/2` y `{"status":"UP"}` en las tres URL. El frontend
queda en `http://localhost:18000` y la API directa en `http://localhost:18080`.

**Es normal ver arranques fallidos del backend.** En el historial de tareas aparecen una o dos
`Failed ... "task: non-zero exit (1)"` por réplica antes de la que está `Running`:

```bash
docker stack ps --no-trunc transformers-local
```

Swarm no espera a que MySQL esté listo antes de arrancar el backend, así que este muere y se reintenta
hasta que la base acepta conexiones. Es esperado y se recupera solo. Distinto es que **siga** fallando
después de la convergencia: eso ya es un fallo real, y se mira con
`docker service logs --tail 100 transformers-local_backend`.

### Paso 4. Cargar datos de demostración

El stack arranca **vacío**: sin cuentas y sin productos. Se puede crear una cuenta desde la propia
aplicación, porque el registro de vendedor es público, pero el catálogo seguiría vacío y no habría
nada que demostrar.

Los guiones del repositorio funcionan contra el MySQL del stack; solo hay que apuntarles al
contenedor, cuyo nombre lo pone Swarm:

```bash
MYSQLC=$(docker ps --filter "label=com.docker.swarm.service.name=transformers-local_mysql" \
  --format '{{.Names}}' | head -1)

DB_PASSWORD='UnaClaveLocalApp' DEMO_PASSWORD='UnaClaveDemo2026!' MYSQL_CONTAINER="$MYSQLC" \
  ./scripts/cu23-demo-seed.sh
DB_PASSWORD='UnaClaveLocalApp' DEMO_PASSWORD='UnaClaveDemo2026!' MYSQL_CONTAINER="$MYSQLC" \
  ./scripts/cu24-25-demo-seed.sh
```

`DB_PASSWORD` es la del archivo del paso 1. Eso deja las cuentas `vendedor.demo@example.com` y
`comprador.demo@example.com`, el catálogo con productos y los pedidos de demostración.

La primera cuenta con rol VENDEDOR recibe la tienda principal, así que puede gestionar sus pedidos.
Si se prefiere asignarla a otra cuenta, está `MAIN_STORE_OWNER_EMAIL` (ver más abajo).

### Paso 5. Usarlo

Abrir `http://localhost:18000`, entrar con una de las cuentas del paso anterior y cambiar el rol
activo en *Ver cuenta*. Recordar **recargar la página** después de entrar: el catálogo se pide antes
del login.

### Paso 6. El webhook logístico (CU-24 y CU-25)

`deploy.sh` crea un tercer secret con el secreto del webhook. Si no se le da un archivo, lo **genera
aleatorio**, de modo que el webhook queda abierto. Comprobarlo:

```bash
curl -s -X POST http://localhost:18000/api/logistics/webhooks/shipments \
  -H 'Content-Type: application/json' -d '{"eventId":"x"}'
```

Debe responder 401 `WEBHOOK_SIGNATURE_INVALID`. Si responde 401 `WEBHOOK_NOT_CONFIGURED`, el backend
arrancó sin secreto.

Para **enviar novedades a mano** hace falta conocer el valor, así que hay que crearlo antes de
desplegar:

```bash
printf 'un-secreto-largo-y-aleatorio' > ~/swarm-secrets/webhook.txt
chmod 600 ~/swarm-secrets/webhook.txt
export LOGISTICS_WEBHOOK_SECRET_FILE=~/swarm-secrets/webhook.txt
./deploy.sh --local
```

Y después:

```bash
LOGISTICS_WEBHOOK_SECRET='un-secreto-largo-y-aleatorio' BACKEND_URL=http://localhost:18000 \
  ./scripts/cu24-25-demo-events.sh shipment 7 PICKED_UP
```

Los secrets son inmutables: si ya existe con otro valor, hay que cambiar `LOGISTICS_WEBHOOK_SECRET`
al nombre de uno nuevo, no reescribir el que hay.

### Paso 7. Repetir o retirar

Volver a ejecutar el mismo comando reutiliza secrets, volumen y datos. Para retirarlo, la sección
**Retirada explícita** al final de este documento.

## Futuro cluster con dos computadores

Usar motores Docker Linux con IPs alcanzables entre sí. La VM de Docker Desktop,
NAT, VPN y firewalls pueden impedir que dos Macs formen una overlay funcional.
No reutilizar la dirección loopback del ensayo local como dirección de cluster.

En un entorno nuevo, planificado y sin workloads que deban preservarse:

```bash
# Manager: sustituir por su IP privada real.
docker swarm init --advertise-addr <IP_PRIVADA_MANAGER>
docker swarm join-token worker
```

Ejecutar **en el segundo equipo** el comando `docker swarm join` mostrado por
Docker. No guardar el token de join en el repositorio ni en evidencias públicas.
Permitir entre nodos de confianza TCP 2377, TCP/UDP 7946 y UDP 4789; no exponer
estos puertos de control/overlay a Internet. Permitir el puerto publicado del
backend a los clientes previstos.

En el manager:

```bash
docker node ls
docker node update --label-add backend_zone=manager <ID_MANAGER>
docker node update --label-add backend_zone=worker <ID_WORKER>
docker login ghcr.io
MYSQL_NODE_ID=<ID_MANAGER> \
BACKEND_IMAGE_TAG=sha-<SHA_PUBLICADO> \
BACKEND_HOST_PORT=8080 \
./deploy.sh
```

La preferencia `spread: node.labels.backend_zone` es una estrategia soportada por
Swarm para repartir por valores de etiqueta. Con etiquetas distintas, capacidad
y nodos disponibles, favorece esta distribución:

```text
Manager: backend réplica 1 + MySQL (volumen local)
Worker:  backend réplica 2
```

Es una preferencia, no una restricción de una réplica por nodo. Nodos sin etiqueta
forman otro grupo y siguen siendo elegibles. Si cae un worker, ambas réplicas
pueden ejecutarse en el manager si hay capacidad. Por eso no se usa
`max_replicas_per_node: 1`, que impediría dos réplicas en el ensayo local y podría
impedir recuperación con un único nodo disponible.

Referencias: [placement](https://docs.docker.com/engine/swarm/services/),
[routing mesh](https://docs.docker.com/engine/swarm/ingress/).

## Prueba de disponibilidad: tráfico, caída y recuperación

Usando el nombre `transformers-local` y puerto 18080; ajustar en el cluster real.
Primero confirmar 2/2 y localizar el nodo y el ID completo de una task:

```bash
docker service ps --no-trunc transformers-local_backend
```

Desde otra terminal, iniciar tráfico continuo a través del frontend. Dejarlo
activo hasta que termine la recuperación; `DURATION` debe ser mayor que el tiempo
previsto para reemplazar la task:

```bash
BASE_URL=http://localhost:18000 VUS=50 DURATION=3m \
  k6 run --summary-export availability-k6.json scripts/k6/catalog-browse.js
```

Se usa el guion de catálogo y no el de registro: son peticiones de lectura, así que el tráfico se
mantiene constante sin saturar la CPU con el cifrado de contraseñas, y lo que se observe durante la
caída se atribuye a la réplica que falta y no a una máquina sin CPU disponible.

En el manager, obtener el contenedor de la task seleccionada:

```bash
docker inspect --type task <ID_TASK> --format '{{.Status.ContainerStatus.ContainerID}}'
```

**Solo durante la demostración**, ejecutar en el nodo donde vive esa task:

```bash
docker kill <ID_CONTENEDOR_BACKEND_SELECCIONADO>
```

Y revisar desde el manager:

```bash
docker service ps --no-trunc transformers-local_backend
docker stack services transformers-local
```

Esperado a comprobar: la réplica restante atiende tráfico y Swarm crea una task
nueva hasta recuperar 2/2. Verificarlo explícitamente con:

```bash
docker service ps --no-trunc transformers-local_backend
docker service ls --filter name=transformers-local_backend
curl --fail http://localhost:18000/api/actuator/health/readiness
```

El resumen y `availability-k6.json` registran P95, throughput y error rate
durante la caída. Puede haber conexiones en vuelo fallidas o errores transitorios;
no prometer cero errores. Matar una task no demuestra tolerancia a la pérdida de
un computador; esa prueba, la overlay y las arquitecturas deben validarse con los
dos equipos.

### Medición hecha

Ejecutada en Docker Desktop sobre el stack de un nodo, con tráfico continuo al readiness a través del
frontend (una petición cada 0,2 s) mientras se mataba una réplica del backend:

| Qué | Resultado |
| --- | --- |
| Peticiones durante la prueba | 200 |
| Respuestas HTTP 200 | 200 (ninguna falló) |
| Tiempo en volver a 2/2 | 29 s |
| Estado de la task muerta | `Failed ... "task: non-zero exit (137)"`, reemplazada por una nueva |

Que no fallara ninguna petición no está garantizado: depende de si alguna conexión estaba en vuelo
hacia la réplica que se mató. Lo que sí es repetible es que **el servicio siguió atendiendo** con una
sola réplica y que Swarm reemplazó la caída sin intervención.

## Prueba de performance y comparación ASR

Hay **dos guiones**, porque miden cosas distintas y el ASR (sección 36 de las decisiones
arquitectónicas) fija metas por endpoint, no un único número:

| Guion | Qué ejercita | Meta del ASR |
| --- | --- | --- |
| `scripts/k6/catalog-browse.js` | Navegación del catálogo: listar productos y abrir el detalle | P95 `<= 3 s`, error `< 2 %` |
| `scripts/k6/seller-register.js` | Registro de vendedor, una escritura completa | P95 `<= 4 s` global, error `< 2 %` |

**Cuál acredita el ASR de 100 usuarios.** El de catálogo. Es lo que hace la mayoría de las personas
la mayor parte del tiempo, y ejercita el camino completo: Nginx, las réplicas del backend, el pool de
conexiones, Hibernate y MySQL.

**Por qué el de registro se presenta aparte.** Cifra la contraseña con BCrypt de coste 12, que está
hecho para ser lento a propósito. Un registro cuesta del orden de 0,3 s de CPU **sin nadie más
conectado**, así que con 100 usuarios en paralelo la máquina se satura y el P95 mide el cifrado, no la
plataforma. Además nadie se registra cien veces por minuto. Sirve para comparar corridas entre sí y
para ver cómo se comporta una escritura bajo carga, no para acreditar el tiempo de respuesta general.

Ninguno de los dos define un mínimo de throughput, porque el ASR no lo fija: se informa
(`catalog_throughput`, `seller_register_throughput`) para comparar corridas.

### Ejecutar

k6 no viene instalado. Se puede instalar (`winget install k6 --source winget`) o usar su imagen, que
es lo que se probó aquí. Desde la raíz del repositorio, en PowerShell:

```powershell
docker run --rm -v "${PWD}\scripts\k6:/scripts" `
  -e BASE_URL=http://host.docker.internal:4300 `
  -e CATALOG_EMAIL=demo@marketplace.local -e CATALOG_PASSWORD=MarketplaceDemo123! `
  -e VUS=50 -e DURATION=1m `
  grafana/k6 run --summary-export /scripts/catalogo-50.json /scripts/catalog-browse.js
```

Y repetir con `VUS=100`, que es la corrida que acredita el ASR. Lo mismo para el otro guion cambiando
el archivo por `seller-register.js` y el nombre del resumen.

Desde un contenedor, el Marketplace publicado en el host se alcanza por `host.docker.internal`; con k6
instalado en la máquina se usa `localhost`. Apuntar al **frontend** (4300 en Compose, 18000 con
`./deploy.sh --local`, 80 en el cluster) para medir el camino real, incluido Nginx y el reparto entre
réplicas; apuntar directamente al backend solo si se quiere aislar la API.

Variables de los dos guiones: `BASE_URL`, `VUS`, `DURATION`, `P95_LIMIT_MS` y `ERROR_RATE_LIMIT`. No
relajar los dos últimos cuando se esté acreditando el ASR. El de catálogo acepta además
`CATALOG_EMAIL` y `CATALOG_PASSWORD`; si esa cuenta no existe, la crea al empezar, porque el registro
es público.

k6 termina con código 0 si se cumplen los umbrales y distinto de 0 si alguno se cruza, así que sirve
tal cual en un pipeline.

### Detalle que cuesta descubrir

El catálogo exige sesión: sin ella responde 401. Cada usuario virtual inicia sesión **una sola vez** y
reutiliza su cookie, porque el login también usa BCrypt y hacerlo en cada iteración volvería a medir el
cifrado. Para eso el guion crea su propio frasco de cookies (`new http.CookieJar()`): k6 reinicia el
frasco por defecto al empezar cada iteración, y sin ese detalle la sesión se pierde y todo responde 401
a partir de la segunda vuelta.

### Medición de referencia

Con 5 usuarios virtuales contra un despliegue de desarrollo en un portátil, para tener un punto de
partida, no para acreditar nada: P95 de 46 ms en listado y en detalle, 0 % de error y unas 60
operaciones por segundo. Las corridas de 50 y 100 usuarios son las que hay que guardar como evidencia.

## Retirada explícita

Estos comandos **detienen el stack**; no ejecutarlos si se quiere conservar el
servicio en marcha:

```bash
docker stack rm transformers-local
docker stack ps transformers-local
```

La retirada es asíncrona. Esperar a que desaparezcan sus servicios/tasks antes de
otros pasos. Los secrets externos y el volumen quedan conservados. No borrar el
volumen: contiene la base. Si se desea reutilizarlo, conservar también sus secrets
y credenciales. Para retirar únicamente los secrets, cuando ya no se usen:

```bash
docker secret rm transformers-local_db_password_v1 transformers-local_mysql_root_password_v1
```

Solo si se creó un Swarm **exclusivo de prueba**, no quedan otros stacks/servicios
y ya no se necesitan sus secrets/metadatos:

```bash
docker service ls
docker swarm leave --force
```

Salir destruye el estado Swarm de este manager y no es parte del despliegue ni
una limpieza automática. No ejecutarlo sobre un cluster real.

## Evidencia

Guardar validación estática, commit/tag/digest de imagen, `docker node ls`, servicios
1/1 y 2/2, tasks/nodos, estados healthy, respuestas Actuator y logs de conexión a
`mysql:3306`. Capturar referencias de secrets y arrays Env sin valores sensibles.
No guardar tokens, contenidos de secrets ni credenciales. Para la futura prueba
de fallo, guardar la serie de HTTP, task anterior/nueva y tiempo de recuperación.

Separar siempre resultados observados de resultados esperados. Un bloqueo de
autenticación o arquitectura significa que el despliegue real sigue sin validar.
