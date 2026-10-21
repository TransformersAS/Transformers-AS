# Pruebas en vivo de atributos de calidad

Cambios locales posteriores a la auditoría del SHA `770f3c6da98a1885b69bf43c606a60986b2468e6`. No están publicados ni desplegados. **No se modificaron CUs, frontend funcional, backend, seeds, tests de CUs ni gates de cobertura.** Esta receta sustituye las instrucciones anteriores de medición manual cuando se usa la versión local modificada.

## Alcance respecto al enunciado

| Exigencia | Soporte disponible | Qué falta comprobar en el entorno real |
|---|---|---|
| Pruebas en vivo y resultados cuantitativos | Ejecutor de performance 50/100 VUs y observador de disponibilidad; logs, JSON, SHA/árbol de trabajo y versión k6 | Ejecutarlos contra el sistema real, guardar resultados sin escoger solo corridas verdes |
| Dos o más computadores | Stack limita a una réplica frontend/backend por nodo; deploy real exige ≥2 nodos Linux Ready/Active y comprueba placement al converger | Asociar cada NodeID a un computador físico diferente; resolver red de Docker Desktop |
| Inicio por único script | `bash deploy.sh`, cluster y credenciales previamente configurados | Ejecutarlo en el manager; no confundir preparación del cluster con arranque de servicios |
| Pipeline CI/CD | Publicación espera backend, E2E y verificación de herramientas; CD existente depende de publicación | Runner `self-hosted, swarm-manager` operativo, acceso GHCR y variables production; nueva ejecución real |
| Todos los atributos vistos en clase | Se corrigió lo identificable en repo: desempeño, disponibilidad y desplegabilidad | El enunciado no enumera otros atributos ni componentes de clase; SAD/SRS original no está disponible aquí |
| 100 % integración backend | Sin cambios: requisito asociado a CUs, fuera del alcance solicitado | No acreditado por esta tarea; no se rebajó ni maquilló coverage |

No prometer cumplimiento total solo porque estos archivos existen. MySQL sigue sin HA. Dos motores en un solo computador no cumplen distribución física. La prueba de disponibilidad interrumpe UNA réplica de backend, no el nodo ni la DB.

## Qué cambió, lo mínimo

- `catalog-browse.js` exige cuenta de prueba verificada y valida login, listado no vacío y detalle antes de cargar. Ya no registra cuentas durante setup. Mantiene umbrales P95 ≤3 s y error <2 %, añade exigencia de al menos un recorrido completo exitoso y contadores exactos de requests de catálogo, respuestas 200 y fallidas.
- `scripts/quality/run.py` guarda corridas separadas sin sobrescribir evidencia. `performance` ejecuta 50 y 100 VUs durante 1 min cada una, secuencialmente. `availability` ejecuta 50 VUs durante 3 min y observa tasks/readiness desde manager cada ~2 s más latencia de comandos.
- `scripts/quality/run.py deployability` ejecuta `deploy.sh` (con o sin `--local`), mide preparación, convergencia y disponibilidad durante el despliegue, y comprueba réplicas, readiness y que el redespliegue conserva volumen y secrets. Ver la sección de desplegabilidad.
- `deploy.sh` rechaza modo real con menos de dos nodos Linux Ready/Active **antes de pulls/secrets/deploy**. Al converger, frontend y backend deben estar en nodos distintos. `--local` permite dos réplicas por nodo y sigue siendo solo ensayo local.
- `stack.yml` limita cada servicio de aplicación a una réplica por nodo en modo real. Actualiza una a la vez con `stop-first`: evita esperar una tercera task sin slot libre en un cluster de dos nodos. Durante reemplazo puede quedar una réplica; no se promete cero interrupción. No se cambia placement/volumen MySQL.
- CI espera `validate`, `frontend-e2e` y `quality-checks` antes de construir/publicar. No se altera ninguna prueba funcional. Los checks de herramientas **no son una certificación de los atributos reales**.

## Antes del reloj de 20 minutos

1. Confirmar IP manager, red entre motores, dos PCs, NodeIDs y puertos; usar §3 y §14 de la [guía larga](GUIA-SUSTENTACION-EN-VIVO.md).
2. Preparar entorno separado, imágenes publicadas del commit que se vaya a demostrar y cuenta verificada/datos no vacíos. El stack no activa perfil demo por sí solo. No cargar seeds en datos importantes.
3. En manager instalar previamente Python 3 y k6; los comandos siguientes usan k6 nativo. En Windows usar Git Bash con `python3` accesible, o PowerShell con `py` como se indica.
4. Tener dos terminales en manager (medición y control) y una en worker. No correr builds/suites ni relanzar launcher durante carga.
5. No usar el SHA base para identificar modificaciones locales como si ya estuvieran publicadas: el executor guarda SHA y `git status`. Publicar nuevas imágenes requerirá el flujo de entrega autorizado por el equipo; esta tarea no hizo commit/push.

## Performance — cliente de carga

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER/cliente de carga, Bash de macOS/Linux, raíz del repositorio. La IP y puerto son el ejemplo de la guía: confirmar que es EL frontend Swarm medido.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
read -r -p 'Correo de cuenta de prueba verificada: ' CATALOG_EMAIL
read -r -s -p 'Contraseña de prueba: ' CATALOG_PASSWORD
printf '\n'
export CATALOG_EMAIL CATALOG_PASSWORD
python3 scripts/quality/run.py performance --base-url http://192.168.40.13:18000
```

La ruta cd anterior es solo el Mac de Sofía. En otro PC entrar a su raíz real. Las credenciales se heredan por entorno; no se imprimen ni se guardan en los JSON propios del executor. No mostrar inspect de procesos/entornos en la proyección.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows cliente, PowerShell, YA situado en la raíz real del repo; alternativa al bloque Bash.**
```powershell
$env:CATALOG_EMAIL = Read-Host 'Correo de cuenta de prueba verificada'
$claveCarga = Read-Host 'Contraseña de prueba' -AsSecureString
$env:CATALOG_PASSWORD = [System.Net.NetworkCredential]::new('', $claveCarga).Password
py scripts/quality/run.py performance --base-url http://192.168.40.13:18000
```

🟢 Esperado: ambas corridas completas, exit 0, métricas y thresholds satisfechos. 🔴 Cuenta inválida/catálogo vacío: aborta setup, no acredita atributo. El executor conserva el FAIL y aun así permite obtener la segunda corrida si la primera solo falló por thresholds. Elimina overrides heredados `P95_LIMIT_MS` y `ERROR_RATE_LIMIT`; no rebaja límites.

Salida en `artifacts/quality/<fechaUTC>-<PID>/`: `context.json`, `catalogo-50.log`, `catalogo-50.json`, `catalogo-50-result.json` y equivalentes 100. `artifacts/` ya está ignorado por Git. El log se escribe mientras corre; el executor lo imprime al finalizar cada corrida. Para ver avance, abrir el archivo `.log` de la carpeta anunciada en el editor, en el mismo PC.

Anotar p95 listado, p95 detalle, `catalog_error_rate`, `catalog_throughput` count/rate y `http_reqs`. Nuevos `catalog_requests = catalog_successes + catalog_failures` cuentan HTTP de listado/detalle durante carga, **no** setup/login; successes significa HTTP 200, no certifica semántica completa de un cuerpo inválido. El rate de negocio sigue detectando catálogo vacío. No convertir las Rates HTTP en contadores de éxito con semántica invertida.

## Availability — observar sin matar automáticamente

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal Bash de medición en raíz, MISMAS variables de cuenta ya exportadas.**
```bash
python3 scripts/quality/run.py availability --base-url http://192.168.40.13:18000 --stack transformers
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Windows, PowerShell en raíz, variables de cuenta de su bloque previo; alternativa.**
```powershell
py scripts/quality/run.py availability --base-url http://192.168.40.13:18000 --stack transformers
```

Debe iniciar con dos backends Running en nodos distintos y readiness proxy UP. No basta usar el stack antiguo marketplace del Mac para afirmar despliegue de esta versión.

1. Esperar ~30 s de tráfico. Si k6 aborta por setup, **no matar nada**: leer `.log`.
2. En terminal de control del manager, identificar task y NodeID del worker mediante §3.4 de la guía larga.
3. En worker, comprobar Service y TaskID del contenedor local; ejecutar el kill de §8.3 **solo de esa réplica**. Anotar hora exacta y cronómetro manual; el executor no ejecuta kill.
4. Dejar terminar los 3 min. El observador guarda `availability-samples.jsonl` y `availability-result.json`, junto al resumen/log k6.
5. Abrir resultado y capturar tasks antiguas/nuevas, métricas y tiempos. No marcar prueba física como verificada hasta relacionar NodeIDs con ambos PCs.

**Definición precisa del recovery automático:** desde primera muestra donde falta una task original Running hasta la tercera muestra consecutiva con dos tasks Running en nodos distintos, una nueva task y readiness proxy UP. Es **recuperación observada por sondeo**, no instante exacto del kill ni health individual de cada contenedor. Conserva muestras y valida salud individual manualmente en cada nodo, como §8.4. Si se necesita tiempo desde kill, usar además cronómetro/timestamps manuales.

FAIL si no se observa pérdida/reemplazo, si también desaparece la segunda task original o si falla k6. El JSON no convierte dos nodos en dos computadores: mantiene `physical_computers_verified: false`; acompañarlo con evidencia física. Un redeploy o reinicio ajeno también puede reemplazar task: registrar manualmente la causa de la caída.

## Desplegabilidad — prueba corregida y medida

**Qué se corrigió.** Antes esta sección solo pedía ejecutar `bash deploy.sh` y mirar el mensaje final: no había escenario medible, ni números, ni evidencia guardada, y nunca se había ejecutado. Ahora `scripts/quality/run.py deployability` ejecuta **el mismo comando único** que se usaría a mano, cronometra cada línea de salida y guarda la evidencia junto a las de performance y availability.

**Escenario (ASR de desplegabilidad).**

| Parte | Valor |
|---|---|
| Fuente | Integrante del equipo en el nodo manager |
| Estímulo | Desplegar (o redesplegar) frontend, backend y MySQL |
| Artefacto | `deploy.sh` + `stack.yml`, imágenes `transformers-as-frontend` / `transformers-as-backend`, `mysql:8.4.11` |
| Entorno | Cluster preparado (Swarm, puertos, credenciales); en el ensayo, Swarm de un nodo con `--local` |
| Respuesta | Servicios creados/actualizados, réplicas 2/2, 2/2, 1/1, readiness UP, sin pedir nada por teclado |
| Medida | 1 comando, 0 prompts, convergencia (`docker stack deploy` → 3 sondeos UP seguidos) ≤ 600 s; en redespliegue: mismo volumen MySQL y mismos secrets (mismo ID), disponibilidad del proxy durante el redespliegue |

**Qué mide el ejecutor (cada corrida `deploy-N`).**

- `timings_seconds.total`: inicio de `deploy.sh` → salida del script.
- `preparation_until_stack_deploy`: validaciones, build/pull de imágenes y secrets, hasta la primera línea `Creating|Updating service`.
- `stack_deploy_to_converged`: desde ahí hasta el mensaje `Frontend y API disponibles` (réplicas completas + readiness UP tres veces).
- `services`: réplicas Running por servicio, NodeID de cada task e imagen desplegada.
- `health_probes`: estado y latencia de `/actuator/health/readiness` (backend), `/healthz` y `/api/actuator/health/readiness` (proxy frontend) al terminar.
- `availability_during_deploy`: sondeo del readiness del proxy cada 1 s mientras corre el script.
- Redespliegue: `mysql_volume_preserved` (misma fecha de creación del volumen) y `secrets_preserved` (mismos IDs de secret).
- Modo cluster (sin `--local`): además `app_replicas_on_distinct_nodes` (frontend y backend en ≥ 2 NodeID).

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash (Git Bash en Windows), raíz del repo.** Ensayo de un nodo:
```bash
python3 scripts/quality/run.py deployability --local --runs 2
```
Cluster real de dos PCs (sin `--local`; secrets ya creados o con `DB_PASSWORD_SECRET_FILE` / `MYSQL_ROOT_PASSWORD_SECRET_FILE`):
```bash
python3 scripts/quality/run.py deployability --runs 2
```
La 1.ª corrida es el despliegue; la 2.ª, el redespliegue idempotente sobre el stack que acaba de quedar. En `--local`, si faltan los secrets de base de datos el ejecutor genera contraseñas aleatorias de un solo uso en un directorio temporal que borra al terminar; nunca se escriben en la evidencia. Salida en `artifacts/quality/<fechaUTC>-<PID>/`: `context.json`, `deploy-N.log` (salida completa con tiempo por línea), `deploy-N-result.json` y `summary.json`.

### Resultados de la ejecución — 2026-10-21, PC Windows de Alejandro

Entorno: Docker Desktop 29.8.0 (linux/amd64), Swarm de **un nodo** (`deploy.sh --local`), commit base `6e56c8db` con los cambios de este medidor sin commitear. Evidencia: `artifacts/quality/20261021T215035-21580/`.

| Métrica | deploy-1 (despliegue en frío) | deploy-2 (redespliegue) |
|---|---|---|
| Comandos manuales / prompts | 1 / 0 | 1 / 0 |
| Código de salida de `deploy.sh` | 0 | 0 |
| Tiempo total | **183,3 s** | **31,7 s** |
| Preparación (build + secrets) hasta `stack deploy` | 16,0 s | 14,6 s |
| Convergencia (`stack deploy` → readiness estable) | **167,3 s** (límite 600 s) | **17,1 s** (límite 600 s) |
| Réplicas frontend / backend / mysql | 2/2 · 2/2 · 1/1 | 2/2 · 2/2 · 1/1 |
| Readiness backend / `healthz` / readiness proxy | UP 14,0 ms · UP 22,4 ms · UP 23,2 ms | UP 26,2 ms · UP 4,1 ms · UP 12,5 ms |
| Disponibilidad del proxy durante el script | 23/170 sondeos UP (13,5 %) — esperado: el servicio aún no existía | **31/31 sondeos UP (100 %)** — sin corte |
| Volumen MySQL conservado | n/a (se creó) | Sí (`2026-10-21T21:50:53Z` antes y después) |
| Secrets reutilizados | n/a (se crearon) | Sí: el log muestra 3 × `Reutilizando secret` y los IDs no cambian (`kx6fq6…`, `l4c0us…`, `qr3wnm…`) |

En la 1.ª corrida el tiempo de preparación es bajo porque las capas de build ya estaban en la caché de Docker; en un PC sin caché el build del frontend (`npm ci` + Angular) tarda varios minutos más y cuenta en `preparation_until_stack_deploy`, no en la convergencia.

**Veredicto honesto de esta corrida.** El despliegue y el redespliegue cumplen todas las medidas del escenario. El `summary.json` de esa carpeta dice `measurement_passed: false` por un **error del medidor**, no del sistema: comparaba los secrets por su fecha relativa (`2 minutes ago` → `3 minutes ago`). Se corrigió para comparar por ID; los IDs medidos, arriba, son idénticos. Se conserva la carpeta sin editar.

### Repetición con el medidor corregido — 2026-10-21

Mismo PC y stack (dos redespliegues seguidos sobre el stack del despliegue en frío). Evidencia: `artifacts/quality/20261021T215838-31256/`, `summary.json` con **`measurement_passed: true`** y todos los checks en `true`.

| Métrica | deploy-1 | deploy-2 |
|---|---|---|
| Código de salida / comandos / prompts | 0 / 1 / 0 | 0 / 1 / 0 |
| Tiempo total | 30,8 s | 28,8 s |
| Preparación hasta `stack deploy` | 13,8 s | 11,8 s |
| Convergencia (límite 600 s) | 17,0 s | 16,9 s |
| Réplicas frontend / backend / mysql | 2/2 · 2/2 · 1/1 | 2/2 · 2/2 · 1/1 |
| Latencia readiness backend / `healthz` / readiness proxy | 22,5 · 25,0 · 10,2 ms | 11,4 · 3,5 · 9,9 ms |
| Disponibilidad del proxy durante el script | 30/30 (100 %) | 29/29 (100 %) |
| Volumen MySQL / secrets (por ID) conservados | Sí / Sí | Sí / Sí |

El volumen sigue siendo el creado en el despliegue en frío (`2026-10-21T21:50:53Z`).

**Lo que esta evidencia NO acredita.** Es un nodo: no prueba distribución entre dos computadores (`physical_computers_verified: false`, y en `--local` no se exige `app_replicas_on_distinct_nodes`). Las imágenes se construyeron localmente con la etiqueta `latest`, no se descargaron de GHCR. Un redespliegue sin cambios no reinicia tasks; el 100 % de disponibilidad no demuestra una actualización rodante con imagen nueva. Para la sustentación, repetir el comando sin `--local` en el manager del cluster de dos PCs.

## CI/CD — estado verificado y pasos manuales pendientes

Pipeline: `.github/workflows/backend-ci.yml` (`Backend CI and GHCR`), se dispara con cada push a cualquier rama.

```
quality-checks ┐
frontend-e2e   ├─► publish (solo main: build + push a GHCR) ─► deploy (self-hosted, opcional)
validate       ┘   build   (otras ramas: build sin publicar)
```

### Última ejecución en `main` — consultada el 2026-10-21

Ejecución [#37831494109](https://github.com/TransformersAS/Transformers-AS/actions/runs/37831494109), commit `6e56c8db` (*fix: hace opcional el deploy a Swarm*), resultado **success**.

| Job | Resultado | Duración |
|---|---|---|
| Quality attribute tooling checks | ✅ success | 5 s |
| Frontend E2E with Playwright | ✅ success | 3 min 02 s |
| Maven verification (`validate`) | ✅ success | 7 min 25 s |
| Build and publish to GHCR | ✅ success | 8 min 27 s |
| Build image without publishing | ⏭️ skipped (solo aplica fuera de `main`) | — |
| Deploy to Swarm (self-hosted) | ⏭️ **skipped** | — |

Los jobs corrieron de 01:40:17 a 01:56:13 UTC del 2026-10-09: **15 min 56 s** desde el inicio de las validaciones hasta tener imágenes publicadas en GHCR. Las tres validaciones corren en paralelo; la ruta crítica es Maven (7 min 25 s) + publicación (8 min 27 s). Las cuatro ejecuciones anteriores de `main` (commits `2e8b92e2`, `58bea4b4`, `8fd9cfb8`, `770f3c6d`) quedaron **cancelled**; no se cuentan como éxitos.

📸 Evidencia a adjuntar: captura de la página de esa ejecución (grafo de jobs) y de los paquetes `transformers-as-backend` / `transformers-as-frontend` en GHCR con la etiqueta `sha-6e56c8db…`. La lista de paquetes exige iniciar sesión en GitHub; no se pudo consultar sin autenticación.

### Qué está automatizado y qué sigue siendo manual

| Paso | Estado |
|---|---|
| Validar backend (Maven), frontend (E2E Playwright) y herramientas de calidad en cada push | ✅ Automático |
| Construir y publicar imágenes inmutables `sha-<commit>` en GHCR al llegar a `main` | ✅ Automático |
| Desplegar en el Swarm desde el pipeline (job `deploy`) | ❌ **Pendiente**: el job existe, pero se salta |
| Desplegar el stack | ⚠️ Manual: `bash deploy.sh` en el manager (un comando, medido arriba) |

**Por qué se salta `deploy`.** Solo corre si se cumplen las dos condiciones siguientes:

1. Variable de repositorio `ENABLE_SWARM_DEPLOY = true` (Settings → Secrets and variables → Actions → Variables). En la ejecución #37831494109, sobre `main`, el job se saltó: la variable no valía `true`. El commit `6e56c8db` volvió el job opcional con esta variable, para que la falta de runner no deje el pipeline en espera.
2. Un runner `self-hosted` con etiqueta `swarm-manager` (no hay evidencia de que exista; verificarlo en Settings → Actions → Runners) registrado en el PC manager del cluster, con Docker, acceso a GHCR (`docker login ghcr.io`) y el Swarm de dos nodos ya formado.

**Intervención manual que queda, aunque se active el job:**

- Formar el cluster: `docker swarm init` en el manager y `docker swarm join` en el worker, con red entre ambos PCs (§14.2 de la [guía en vivo](GUIA-SUSTENTACION-EN-VIVO.md)).
- Registrar y mantener encendido el runner self-hosted en el manager.
- Crear en el environment `production` las variables `STACK_NAME`, `DB_PASSWORD_SECRET_FILE`, `MYSQL_ROOT_PASSWORD_SECRET_FILE` y `LOGISTICS_WEBHOOK_SECRET_FILE`, y dejar esos archivos de contraseña en el manager (o crear los secrets de Docker una vez a mano).
- Cargar datos de prueba si la demostración los necesita: el stack no carga seeds por sí solo.

Con eso listo, el job ejecuta el mismo `bash deploy.sh` medido en la sección anterior, con las imágenes `sha-<commit>` recién publicadas. Hasta entonces, el CD termina en la publicación a GHCR y el despliegue es el script único lanzado a mano.

## Validación de las herramientas, no del ASR

**DÓNDE EJECUTAR ESTO — 💻 PC de desarrollo, Bash, raíz; solo tests de herramientas y sintaxis, sin levantar/matar servicios reales.**
```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/quality/tests -v
bash -n deploy.sh
```

Los tests HTTP usan un servidor simulado local con k6, no el Marketplace. Si k6 falta, esos tests se marcan skipped; no son una corrida de carga válida para presentar al profesor. Las pruebas del observador cubren ausencia de caída, doble caída, falta de distribución y recuperación estable. No afirmar atributos implementados correctamente hasta completar las mediciones reales con evidencia.

**Resultado observado de validación local (Mac de Sofía):** 11 tests de herramientas aprobados, incluidos cuatro contratos HTTP con k6 contra servidor simulado y el rechazo de despliegue real de un solo nodo antes de mutaciones. También pasaron sintaxis Bash/JavaScript, parseo del workflow y referencias de sus jobs, render de stack para límites 1 y 2 por nodo y revisión de whitespace. No se ejecutaron carga, caída de réplica ni deploy contra el Marketplace activo; no hay resultado ASR real nuevo que atribuir a estas validaciones.
