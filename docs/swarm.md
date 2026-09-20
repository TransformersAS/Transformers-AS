# Baseline Docker Swarm

Esta configuración es independiente de `compose.yaml`. No incluye CD remoto ni
runner self-hosted. Ejecutar `deploy.sh` desde un manager con Docker y curl.

## Arquitectura y límites

- `backend`: imagen privada de GHCR, dos réplicas, healthcheck **liveness** heredado
  de la imagen, restart con 10 s de espera y actualización de una réplica a la vez.
  `start-first` requiere capacidad temporal para una tercera réplica. Un fallo
  detectado durante la actualización solicita rollback de la aplicación.
- `mysql`: MySQL 8.4.11 LTS, una réplica, volumen local `<stack>_mysql_data`, fijado al ID
  de un manager concreto. Las actualizaciones usan `stop-first` para evitar dos
  escritores simultáneos sobre ese volumen. **No hay HA ni replicación de MySQL.**
- Ambos servicios comparten una red overlay `<stack>_internal`. El backend usa
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

Se crean dos secrets externos al stack, por defecto:

- `<stack>_db_password_v1`
- `<stack>_mysql_root_password_v1`

MySQL usa `MYSQL_PASSWORD_FILE` y `MYSQL_ROOT_PASSWORD_FILE`. La imagen oficial
lee esos archivos al inicializar una base vacía. El backend monta solo la clave
de aplicación como `/run/secrets/DB_PASSWORD`, legible por UID/GID 10001, modo
0400, y usa `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`. Spring Boot incorpora
el nombre del archivo como propiedad `DB_PASSWORD`, resolviendo el placeholder
actual sin cambiar el código ni exportar la contraseña como variable de entorno.

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

`BACKEND_IMAGE_TAG=latest` sirve para pruebas iniciales. Preferir
`BACKEND_IMAGE_TAG=sha-<SHA completo publicado>` para identificar una versión.
Swarm resuelve el digest del registro. Las etiquetas pueden cambiar. MySQL está fijado a `8.4.11`;
fijar una versión no equivale a fijar todo por digest.

Verificar arquitecturas antes de desplegar:

```bash
docker info --format '{{.Architecture}}'
docker manifest inspect ghcr.io/transformersas/transformers-as-backend:latest
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

## Despliegue local de un solo nodo

Docker Desktop debe estar activo. El modo local puede inicializar un Swarm nuevo
con `advertise-addr 127.0.0.1`; no es una dirección para incorporar otros equipos.
No abandona ni reinicializa un Swarm existente y rechaza un worker. No detiene
Compose ni elimina volúmenes. El puerto local predeterminado es 18080 para evitar
el backend de desarrollo en 8080. El routing mesh puede ser accesible desde la LAN.

```bash
docker login ghcr.io
STACK_NAME=transformers-local \
BACKEND_IMAGE_TAG=latest \
BACKEND_HOST_PORT=18080 \
./deploy.sh --local
```

Introducir las contraseñas de prueba cuando se soliciten. Repetir el mismo comando
reutiliza secrets y volumen. En automatización añadir las dos variables de rutas
de secret antes del comando. El script no elimina recursos ante un timeout, para
permitir diagnóstico. Es posible una creación parcial si falla algún paso.

Comprobar:

```bash
docker info --format '{{.Swarm.LocalNodeState}}'
docker stack services transformers-local
docker stack ps --no-trunc transformers-local
docker service ls
docker service logs --tail 100 transformers-local_backend
curl --fail http://localhost:18080/actuator/health/liveness
curl --fail http://localhost:18080/actuator/health/readiness
curl --fail http://localhost:18080/actuator/health

# En cada nodo, comprobar los contenedores locales de cada servicio:
for service in backend mysql; do
  for container in $(docker ps -q --filter "label=com.docker.swarm.service.name=transformers-local_${service}"); do
    docker inspect --format '{{.Name}} {{.State.Health.Status}}' "$container"
  done
done

docker service inspect transformers-local_backend \
  --format '{{json .Spec.TaskTemplate.ContainerSpec.Env}}'
docker service inspect transformers-local_mysql \
  --format '{{json .Spec.TaskTemplate.ContainerSpec.Env}}'
```

Esperado: mysql 1/1, backend 2/2, contenedores healthy, Actuator UP, URL JDBC
`mysql:3306`, sin contraseñas en los arrays Env. Una respuesta de routing mesh no
demuestra que ambas réplicas respondieron. No equivale a una prueba multi-nodo.

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

## Demostración futura de pérdida de una réplica (NO ejecutada en esta fase)

Usando el nombre `transformers-local` y puerto 18080; ajustar en el cluster real.
Primero confirmar 2/2 y localizar el nodo y el ID completo de una task:

```bash
docker service ps --no-trunc transformers-local_backend
```

Desde otra terminal, emitir peticiones durante un intervalo limitado:

```bash
for attempt in $(seq 1 60); do
  date -u
  curl --max-time 3 --silent --show-error --output /dev/null \
    --write-out '%{http_code}\n' http://localhost:18080/actuator/health/readiness
  sleep 1
done
```

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
nueva hasta recuperar 2/2. Puede haber conexiones en vuelo fallidas o errores
transitorios; no prometer cero errores. Registrar los códigos HTTP y tiempos
reales. Matar una task no demuestra tolerancia a la pérdida de un computador;
esa prueba, la overlay y las arquitecturas deben validarse con los dos equipos.

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
