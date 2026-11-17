# Disponibilidad (ASR-004): diseño, pruebas y resultados

Documento único del atributo de disponibilidad del Marketplace: qué exige el requisito, qué tácticas se
implementaron y dónde, cómo se prueba (en un computador y en el clúster de dos computadores) y qué resultados hay.
Reúne lo que estaba repartido en otros documentos, enlazados en [Documentos relacionados](#documentos-relacionados).

La medición automática de recuperación y las pruebas de inyección de fallos que respaldan cada táctica están en
[ASR-004: evidencia de continuidad y recuperación](asr-004-evidencia.md); aquí se resumen sus resultados.

## Contenido

- [Requisito y escenario](#requisito-y-escenario)
- [Tácticas implementadas](#tácticas-implementadas)
- [Comprobaciones de salud](#comprobaciones-de-salud)
- [Limitaciones conocidas](#limitaciones-conocidas)
- [Cómo se prueba](#cómo-se-prueba)
- [Resultados](#resultados)
- [Documentos relacionados](#documentos-relacionados)

## Requisito y escenario

**ASR-004 — Continuidad y recuperación de funciones críticas** (SAD, sección 5).

| Elemento | Valor |
| --- | --- |
| Fuente | Falla interna |
| Estímulo | Se cae una réplica del backend (o un computador del clúster) |
| Artefacto | Servicios `frontend` y `backend` en Docker Swarm |
| Ambiente | Operación con carga: 50 usuarios virtuales navegando el catálogo |
| Respuesta | La otra réplica sigue atendiendo y Swarm reemplaza la caída |
| Medida | **≥ 99 %** de respuestas válidas durante la prueba y servicio restablecido en **≤ 5 minutos** |

## Tácticas implementadas

| Táctica | Qué hace | Dónde está |
| --- | --- | --- |
| **Redundancia activa** | 2 réplicas de `frontend` (Nginx) y 2 de `backend`. En el clúster real, como máximo una réplica de cada servicio por nodo (`max_replicas_per_node: 1`) y repartidas por la etiqueta `backend_zone`, para que la caída de un computador no se lleve las dos | `stack.yml` → `deploy.replicas`, `deploy.placement` |
| **Reintento en el proxy** | Si una réplica del backend no acepta la conexión o no responde en 2 s, Nginx reenvía la petición a la otra (máximo 2 intentos). Solo reintenta errores de conexión y tiempo de espera: un 5xx de la aplicación no se repite y nunca reenvía un POST/PUT/DELETE ya enviado | `frontend/nginx.conf` → `proxy_next_upstream error timeout`, `proxy_next_upstream_tries 2`, `proxy_connect_timeout 2s` |
| **Balanceo de carga** | Nginx reparte las peticiones `/api` entre las réplicas del backend, resueltas por el DNS interno de Docker | `frontend/nginx.conf` → `upstream backend_upstream` con `resolve` |
| **Estado compartido (sesiones)** | Las sesiones se guardan en MySQL con Spring Session JDBC, no en la memoria de cada réplica. Si cae la réplica que atendía a un usuario, la otra lo reconoce sin pedirle que inicie sesión de nuevo | `backend/demo/pom.xml` (`spring-boot-starter-session-jdbc`), `application.properties` (`spring.session.*`) |
| **Reinicio automático** | Si una tarea termina o falla, Swarm crea otra (`condition: any`, espera de 10 s) | `stack.yml` → `deploy.restart_policy` |
| **Actualización gradual y reversión** | Se actualiza una réplica a la vez; si la nueva versión no queda sana durante 90 s, Swarm vuelve automáticamente a la anterior | `stack.yml` → `update_config` (`parallelism: 1`, `failure_action: rollback`, `monitor: 90s`) y `rollback_config` |
| **Degradación controlada** | Las integraciones secundarias no tumban la compra: timeout de 10 s y *circuit breaker* (Resilience4j) en logística y notificaciones; recomendaciones con alternativa local si Gemini no responde | `application.properties` → `logistics.http.timeout`, `notifications.http.timeout`, `resilience4j.circuitbreaker.*` |
| **Persistencia de datos** | MySQL guarda sus datos en un volumen persistente; si su contenedor se reinicia, los datos se conservan | `stack.yml` → volumen `mysql_data` |

## Comprobaciones de salud

| Servicio | Comprobación | Qué verifica | Dónde |
| --- | --- | --- | --- |
| backend | `/actuator/health/liveness` cada 10 s (5 reintentos, 60 s de arranque) | Que el proceso Java está vivo | `backend/demo/Dockerfile` → `HEALTHCHECK` |
| backend | `/actuator/health/readiness` | Que puede atender, **incluida la conexión a MySQL** (`readinessState,db`) | `application.properties` → `management.endpoint.health.group.readiness.include` |
| frontend | `/healthz` cada 10 s | Que Nginx responde | `frontend/Dockerfile` → `HEALTHCHECK`, `frontend/nginx.conf` |
| frontend → backend | `/api/actuator/health/readiness` | La cadena completa: Nginx → backend → MySQL | `frontend/nginx.conf` |
| mysql | `SELECT 1` cada 10 s | Que la base acepta consultas | `stack.yml` → `healthcheck` |

`deploy.sh` no da el despliegue por terminado hasta que las réplicas convergen y la cadena completa responde `UP`
varias veces seguidas (plazo máximo de 600 s).

Además, Nginx agrega a cada respuesta las cabeceras `X-Served-By-Frontend` y `X-Served-By-Backend`, que indican qué
réplica atendió la petición (y, si hubo reintento, las dos). Permiten comprobar el reparto y ver un reintento.

## Limitaciones conocidas

- **MySQL no tiene alta disponibilidad.** Es una sola réplica, fijada al nodo manager. Si falla, Swarm la reinicia y
  los datos se conservan en el volumen, pero mientras tanto las operaciones que necesitan la base se detienen: en la
  medición del 17/11/2026 se recuperó en 27 s, con 8 de 12 consultas fallidas durante ese lapso. Si cae el computador
  manager, el sistema se detiene hasta recuperarlo. Es un riesgo aceptado del alcance del proyecto (SAD, sección 14).
- **Durante el reemplazo puede quedar una sola réplica.** Con dos nodos y una réplica por nodo no hay un tercer lugar
  libre, por eso las actualizaciones usan `stop-first`: se detiene la réplica vieja antes de iniciar la nueva. No se
  promete cero interrupción.
- **En un solo computador la prueba no es completa.** Las dos réplicas quedan en el mismo equipo y la réplica nueva
  se crea ahí mismo; la caída de un computador no se puede probar.

## Cómo se prueba

### Opción A: en un solo computador (ensayo local)

Sirve para practicar y para obtener una primera evidencia. Se ejecuta en **Git Bash** con **Docker Desktop**
(no con el Docker de WSL; ver `docs/despliegue/docker-swarm.md`). Requiere `k6` (`winget install k6 --source winget`).

> El script `run.py availability` exige que la réplica nueva quede en **otro nodo** para dar por buena la
> recuperación, así que en un solo computador siempre la marca como no observada. En local hay dos formas de medir.

**Forma rápida (automática):** con el stack local arriba (paso 1), ejecutar

```bash
STACK=transformers-local bash scripts/quality/asr004-recuperacion.sh
```

El script tumba una réplica del backend y luego MySQL, consulta el catálogo cada segundo y mide cuánto tarda cada
falla en recuperarse frente al límite de 300 s. Deja `resultado.json` y `sondeo.csv` en
`artifacts/quality/asr004-<fecha>/`. Detalle en [asr-004-evidencia.md](asr-004-evidencia.md).

**Forma con carga (manual):** reproduce el escenario completo del ASR, con 50 usuarios virtuales navegando mientras
cae la réplica. Pasos 1 a 5:

**1. Levantar el stack local con 2 réplicas** (desde la raíz del repositorio):

```bash
mkdir -p ~/swarm-secrets
printf 'UnaClaveLocalApp'   > ~/swarm-secrets/db.txt
printf 'OtraClaveLocalRoot' > ~/swarm-secrets/root.txt
export STACK_NAME=transformers-local
export DB_PASSWORD_SECRET_FILE=~/swarm-secrets/db.txt
export MYSQL_ROOT_PASSWORD_SECRET_FILE=~/swarm-secrets/root.txt
./deploy.sh --local
docker stack services transformers-local                    # backend 2/2, frontend 2/2, mysql 1/1
curl http://localhost:18000/api/actuator/health/readiness    # {"status":"UP"}
```

**2. Terminal A — carga de 50 usuarios durante 3 minutos:**

```bash
export CATALOG_EMAIL=comprador.demo@example.com
export CATALOG_PASSWORD='MarketplaceDemo123!'
mkdir -p artifacts/disponibilidad-local
BASE_URL=http://localhost:18000 VUS=50 DURATION=3m \
  k6 run --summary-export artifacts/disponibilidad-local/k6.json scripts/k6/catalog-browse.js
```

**3. Terminal B — observar réplicas y salud cada 2 segundos:**

```bash
while true; do
  echo "$(date +%T)  $(docker service ps transformers-local_backend --filter desired-state=running --format '{{.Name}}:{{.CurrentState}}' | tr '\n' ' ') readiness=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18000/api/actuator/health/readiness)"
  sleep 2
done | tee artifacts/disponibilidad-local/observacion.log
```

**4. Terminal C — unos 30 s después de iniciar k6, tumbar UNA réplica del backend:**

```bash
date +%T
docker kill $(docker ps -q --filter label=com.docker.swarm.service.name=transformers-local_backend | head -1)
```

**5. Leer el resultado:**

- **Respuestas válidas** = 100 % − `http_req_failed` del resumen de k6. Meta: ≥ 99 %.
- **Tiempo de recuperación** = hora en que la terminal B vuelve a mostrar 2 réplicas `Running` − hora del `kill`.
  Meta: ≤ 5 min.
- Durante todo el tiempo, `readiness` debería seguir en `200`.

**Opcional — ver el reparto entre réplicas:**

```bash
for i in $(seq 10); do curl -s -D - -o /dev/null http://localhost:18000/api/products | grep -i x-served-by-backend; done
```

Deben alternarse dos direcciones distintas.

Al terminar: `docker stack rm transformers-local`.

### Opción B: en el clúster de dos computadores (prueba oficial)

Requiere el stack `transformers` desplegado con `bash deploy.sh` (modo real) en un clúster Swarm de dos computadores
físicos, con `frontend 2/2`, `backend 2/2` y `mysql 1/1`, y una réplica de cada servicio de aplicación por nodo.

**Caída de una réplica bajo carga** — en el manager, con la cuenta de carga exportada:

```bash
python3 scripts/quality/run.py availability --base-url http://IP_MANAGER --stack transformers
```

El script ejecuta k6 con 50 usuarios durante 3 minutos y, en paralelo, observa cada ~2 s las tareas del backend y la
salud a través de Nginx. **No mata nada por sí solo:** a los ~30 s de tráfico, en el **worker**, se detiene
únicamente el contenedor del backend de ese computador (pasos detallados en la guía de sustentación, §8).

**Definición de recuperación usada por el script:** desde la primera muestra en que falta una tarea original hasta
la tercera muestra seguida con dos tareas `Running` en nodos distintos, una de ellas nueva, y salud `UP`. Es
recuperación observada por sondeo, no el instante exacto del `kill`; para medir desde el `kill`, anotar además la
hora a mano.

La prueba falla si no se observa la pérdida y el reemplazo, si también desaparece la otra réplica original o si
falla k6. La evidencia queda en `artifacts/quality/<fecha>-<PID>/` (`availability.log`, el resumen de k6,
`availability-samples.jsonl` y `availability-result.json`).

**Reparto entre réplicas:**

```bash
python3 scripts/quality/run.py balancing --base-url http://IP_MANAGER --stack transformers
```

Hace 200 peticiones sin sesión y 200 con sesión, y comprueba con `X-Served-By-Backend` que cada réplica atiende al
menos el 30 % (`--minimum-share`). Con sesión demuestra además que la sesión compartida funciona en ambas réplicas.

**Apagar un computador:** con la carga corriendo, apagar el **worker** (no el manager, que tiene MySQL) y comprobar
que el sistema sigue respondiendo con las réplicas del manager.

## Resultados

### Medido

| Fecha | Prueba | Entorno | Resultado | Fuente |
| --- | --- | --- | --- | --- |
| 17/11/2026 | **Caída de una réplica del backend** (`docker kill`), catálogo consultado cada segundo | 1 computador (Swarm local) | Recuperación en **55 s** (límite 300 s): **cumple**. **0 de 49** consultas fallidas: la otra réplica atendió todo | [asr-004-evidencia.md](asr-004-evidencia.md) |
| 17/11/2026 | **Caída de MySQL** (`docker kill`), catálogo consultado cada segundo | 1 computador (Swarm local) | Recuperación en **27 s** (límite 300 s): **cumple**. 8 de 12 consultas fallidas mientras se reiniciaba (no tiene réplica) | [asr-004-evidencia.md](asr-004-evidencia.md) |
| 11/11/2026 | Salud a través de Nginx durante un **rollback** del backend (réplicas reemplazadas de una en una) | 1 computador | 273 de 274 comprobaciones respondieron (≈ 99,6 %); 0 errores al arrancar la versión anterior; datos intactos | [mediciones-asr-005-asr-006.md](mediciones-asr-005-asr-006.md), sección 4.2 |

### Pendiente de medir

Completar esta tabla al ejecutar cada prueba. No anotar resultados que no se hayan medido.

| Prueba | Entorno | Respuestas válidas (meta ≥ 99 %) | Recuperación (meta ≤ 5 min) | Cumple | Evidencia |
| --- | --- | --- | --- | --- | --- |
| Caída de una réplica con 50 usuarios en k6 (forma con carga) | 1 computador | | | | `artifacts/disponibilidad-local/` |
| Caída de una réplica bajo carga (opción B) | 2 computadores | | | | `artifacts/quality/…/availability-result.json` |
| Reparto entre réplicas | 2 computadores | Reparto mínimo 30 % por réplica: | — | | `artifacts/quality/…/balancing-result.json` |
| Apagar el computador worker | 2 computadores | | | | Registro manual y capturas |

Las carpetas `artifacts/` están ignoradas por Git: guardar copia de la evidencia (capturas o los JSON) donde el
equipo pueda consultarla, o resumir los números en esta tabla.

## Documentos relacionados

| Documento | Para qué |
| --- | --- |
| [`asr-004-evidencia.md`](asr-004-evidencia.md) | Script de medición de recuperación, resultados del 17/11/2026 y pruebas de inyección de fallos por táctica (rollback de datos, recuperación de pagos, degradación de recomendaciones) |
| [`docs/despliegue/docker-swarm.md`](../despliegue/docker-swarm.md) | Detalle del stack, despliegue local paso a paso y cuentas de demostración |
| [`docs/despliegue/README-cambios-despliegue.md`](../despliegue/README-cambios-despliegue.md) | Cambios en `stack.yml` y `deploy.sh` |
| [`docs/sustentacion/PRUEBAS-ATRIBUTOS-CALIDAD.md`](../sustentacion/PRUEBAS-ATRIBUTOS-CALIDAD.md) | Uso de `scripts/quality/run.py` para todas las pruebas de calidad |
| [`docs/sustentacion/GUIA-SUSTENTACION-EN-VIVO.md`](../sustentacion/GUIA-SUSTENTACION-EN-VIVO.md) | Guion de la sustentación; la prueba de disponibilidad está en §8 |
| [`mediciones-asr-005-asr-006.md`](mediciones-asr-005-asr-006.md) | Mediciones de rendimiento y despliegue, incluido el rollback |
