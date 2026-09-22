# Guía 101 — Sustentación técnica en vivo Transformers

**Este es el guion para tener abierto durante la demostración.** Primero preparar las ventanas de las secciones 1–3. Cuando terminen las diapositivas, seguir las secciones 4–11 en orden. Los problemas y la preparación del cluster están al final: no leerlos mientras todo funciona.

**Actualizada para los cambios locales de pruebas de calidad:** ejecutor `scripts/quality/run.py`, prevalidación de catálogo/cuenta, medición automática de recuperación, una réplica por nodo y publicación CI condicionada a las pruebas. Estos cambios **no se han desplegado ni probado como sistema distribuido durante esta tarea**. No se modifican los guiones de CUs.

🟢 **Esperado** indica lo que debe aparecer al ejecutar. 📌 **Observado/histórico** identifica evidencia existente. 🔴 **Si falla** indica una salida breve, sin detener toda la exposición. 📸 indica captura y ⏱ indica tiempo.

# 0. La ruta que vamos a seguir

| Tiempo desde fin de diapositivas | Pantalla | Persona | Acción |
|---|---|---|---|
| 00:00–01:00 | Sistema ya abierto | Vanessa / responsable funcional | Mostrar launcher listo, GUI y health |
| 01:00–05:00 | Navegador funcional | Responsable de cada flujo | Ejecutar los flujos asignados; no tocar Docker |
| 05:00–07:00 | Reporte de integración | Sofía | Mostrar alcance, porcentaje y gate reales |
| 07:00–11:00 | M2 — PRUEBAS | Sofía | Un comando ejecuta performance 50 y 100 VUs |
| 11:00–15:00 | M2 + W1 + M1 | Sofía y Vanessa | Tráfico 3 min, kill de UNA réplica en worker, recuperación |
| 15:00–18:00 | M1 — CONTROL | Sofía | Dos nodos, distribución y script único de despliegue |
| 18:00–20:00 | GitHub en navegador | Sofía | CI, GHCR, estado CD y artifact launcher |

**Regla de tiempo:** si un problema toma más de 60–90 segundos, guardar el fallo, decir qué no se pudo demostrar y seguir. No cambiar thresholds, datos ni infraestructura para improvisar un PASS.

**No intentar construir imágenes, crear el cluster ni instalar herramientas durante estos 20 minutos.** Esos preparativos pueden tardar mucho más. Para mostrar el arranque por script en vivo, se repite sobre el entorno previamente preparado con las mismas imágenes/configuración.

# 1. Reparto fijo: cada persona sabe su pantalla

Esta guía usa **Sofía en su Mac como manager y cliente de carga**. Vanessa opera el **worker**. Para que el recorrido no esté lleno de alternativas, los comandos principales de Vanessa están escritos para **PowerShell de Windows**; si su computador usa macOS/Linux, dejar preparada de antemano la variante de §13.7. Eso se resuelve **antes**, no frente al profesor. El sistema operativo de Vanessa no quedó confirmado en la auditoría.

| Nombre que usaremos | Computador | Qué contiene | Cuándo se usa |
|---|---|---|---|
| **M1 — CONTROL** | Mac Sofía, manager | Bash, raíz del repo, variables de deploy | Nodos, tasks, health y deploy |
| **M2 — PRUEBAS** | Mac Sofía, mismo manager | Bash, raíz, cuenta de carga exportada | Performance y availability; no cerrar |
| **M3 — EVIDENCIAS** | Mac Sofía | Finder + editor + navegador | Abrir JSON, capturas, coverage y GitHub |
| **W1 — RÉPLICA** | PC Vanessa, worker | PowerShell; container ID preparado | Matar únicamente el backend elegido |
| **GUI FUNCIONAL** | PC de la demo funcional | Navegador con sistema ya iniciado | Flujos asignados; independiente de la terminal k6 |

**Entorno de calidad elegido para el recorrido:** stack `transformers`; frontend Swarm **18000**, backend directo **18090**, MySQL interno **3306 sin publicar**. Son overrides soportados por el script, no defaults. La IP `192.168.40.13` es la candidata de Sofía: verificarla una vez en preparación y guardarla en las variables siguientes. No volver a decidir puertos en cada bloque.

La GUI del launcher Windows es **http://localhost:4300**, con backend 8080 y MySQL host 3307 en ese Windows. **No medir esa GUI y decir que se midió Swarm.** Las pruebas de calidad de este recorrido apuntan al frontend 18000 del manager.

# 2. Qué debe quedar abierto ANTES de empezar

## 2.1 En el Mac de Sofía

1. Conectar cargador. Evitar suspensión durante la presentación desde ajustes del sistema.
2. Abrir **Docker Desktop** y dejarlo ejecutándose. No cerrarlo al cambiar de pantalla.
3. Abrir el repositorio en el editor. Dejar abiertos:
   - Este documento, situado en §4 cuando termine la preparación.
   - `deploy.sh`.
   - `stack.yml`.
   - `.github/workflows/backend-ci.yml`.
4. Abrir **dos ventanas de Terminal** separadas. Identificarlas como M1 — CONTROL y M2 — PRUEBAS, por título o por su posición: M1 izquierda, M2 derecha. No usar una terminal para ambas tareas.
5. Abrir Finder en `artifacts/quality/` si ya existe; el ejecutor la crea al correr por primera vez. No necesita existir en un clon nuevo.
6. Crear/abrir carpeta de capturas `evidencias-sustentacion/` en la raíz.
7. Dejar el navegador con estas pestañas, en este orden:
   - Frontend Swarm: `http://192.168.40.13:18000` **con la IP ya confirmada**.
   - Health frontend: misma dirección con `/healthz`.
   - Readiness proxy: misma dirección con `/api/actuator/health/readiness`.
   - Reporte HTML de cobertura válido, o documento histórico si no existe reporte actual.
   - [Actions del repositorio](https://github.com/TransformersAS/Transformers-AS/actions).
   - Ejecución de **Backend CI and GHCR** que se vaya a mostrar, con SHA visible.
   - Packages de la organización/repositorio: backend y frontend, con tag SHA.
   - Ejecución de **Build Marketplace launcher**, con artifact visible.
8. Si hay Internet ahora, cargar todas las páginas y guardar las capturas identificadas antes de la clase.

## 2.2 En el PC de Vanessa

1. Cargador conectado, Docker Desktop abierto si usa Desktop, motor **Linux** listo.
2. Ventana **W1 — RÉPLICA** abierta y asociada al motor del worker.
3. Si Windows y el launcher es parte de la demo: entrega completa descomprimida, exe arrancado **antes** del cronómetro, navegador abierto en `http://localhost:4300` y ventana “Marketplace está listo” disponible.
4. Si no se usa launcher Windows: dejar abierta la GUI funcional acordada. Mostrar el artifact del exe acredita construcción, no un doble clic que no se hizo.
5. Abrir un cronómetro, sin iniciarlo todavía. Se usará como referencia del instante del kill.
6. Mantener W1 fuera de la proyección mientras se copian tokens o se introducen contraseñas. Ningún secret debe quedar en capturas.

## 2.3 Lo que ya debe estar preparado

- Dos motores en **dos computadores físicos**, cluster formado y comunicación entre nodos funcional.
- Stack `transformers` desplegado, frontend 2/2, backend 2/2, MySQL 1/1; una réplica de cada servicio de aplicación por nodo.
- Imágenes disponibles para ambos motores y GHCR accesible. El runner CI/CD, si se va a afirmar CD operativo, ya debe existir y estar conectado.
- Cuenta **ya verificada**, contraseña conocida y catálogo no vacío en **el entorno Swarm de carga**. El script k6 ya no crea cuentas.
- Python 3 y k6 nativo en el Mac de Sofía. No instalarlos frente al profesor.
- Reporte de integración previamente generado y guardado, si se pretende mostrar porcentaje actual. Correr la suite al mismo tiempo que k6 distorsiona las mediciones.

**Si esto no está preparado, usar §14 antes de iniciar la demo.** La auditoría encontró un solo nodo y un stack antiguo llamado `marketplace`; no asumir que ese estado ya equivale a esta lista.

# 3. Preparación de terminales — hacer una sola vez

## 3.1 M1 — CONTROL: carpeta, entorno y pantallas de prueba

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager, ventana M1 — CONTROL, Terminal. Este bloque entra a la raíz del repositorio y abre Bash.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
bash
```

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz del repo; antes de los 20 minutos.**
```bash
export STACK_NAME=transformers
export FRONTEND_HOST_PORT=18000
export BACKEND_HOST_PORT=18090
read -r -p 'IP LAN confirmada del manager: ' MANAGER_IP
export MANAGER_IP
export QUALITY_URL="http://${MANAGER_IP}:${FRONTEND_HOST_PORT}"
read -r -p 'Tag publicado de backend Y frontend (sha-SHA_COMPLETO): ' BACKEND_IMAGE_TAG
export BACKEND_IMAGE_TAG
export FRONTEND_IMAGE_TAG="$BACKEND_IMAGE_TAG"
mkdir -p evidencias-sustentacion
git rev-parse HEAD
git status --short
docker node ls
docker stack services "$STACK_NAME"
curl --fail --max-time 5 "$QUALITY_URL/healthz"
curl --fail --max-time 5 "$QUALITY_URL/api/actuator/health/readiness"
```

Introducir la IP que se verificó en preparación, por ejemplo `192.168.40.13` si sigue asignada. Introducir **un tag que exista en GHCR para ambas imágenes**; no escribir literalmente `sha-SHA_COMPLETO`. No es una contraseña.

🟢 Esperado: dos nodos Ready/Active; frontend 2/2, backend 2/2, MySQL 1/1; dos JSON con `status: UP`. Si el stack no existe o da error, no está preparado: §14. No empezar el bloque de pruebas esperando que se arregle solo.

**Dejar M1 abierta.** Las variables pertenecen a esta ventana. Cerrar M1 o abrir otra no conserva automáticamente la configuración.

## 3.2 M2 — PRUEBAS: dejar credenciales listas, sin proyectarlas

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager, ventana M2 — PRUEBAS, Terminal nueva.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
bash
```

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M2 — PRUEBAS, Bash, raíz; introducir la MISMA IP que en M1 y la cuenta de carga del Swarm.**
```bash
read -r -p 'IP LAN confirmada del manager: ' MANAGER_IP
export QUALITY_URL="http://${MANAGER_IP}:18000"
read -r -p 'Correo de cuenta de prueba ya verificada: ' CATALOG_EMAIL
read -r -s -p 'Contraseña de esa cuenta: ' CATALOG_PASSWORD
printf '\n'
export CATALOG_EMAIL CATALOG_PASSWORD
python3 --version
k6 version
```

La contraseña no aparece mientras se escribe: es normal. Pulsar Enter una vez. No pegarla dentro de un comando. No cerrar M2 durante la demostración.

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M2 — PRUEBAS, Bash, raíz; preprueba corta ANTES del cronómetro.**
```bash
k6 run -e BASE_URL="$QUALITY_URL" -e VUS=1 -e DURATION=10s scripts/k6/catalog-browse.js
```

🟢 Esperado: login válido, listado/detalle con muestras y al menos un recorrido exitoso. Esta corrida **no acredita 50 ni 100 VUs**, solo evita descubrir credenciales/catálogo incorrectos en plena demo. Si falla, resolver cuenta/URL/datos antes. No cambiar el CU de autenticación ni desactivar verificación.

## 3.3 W1 — RÉPLICA: preparar exactamente qué contenedor se detendrá

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, PC worker Windows, W1 — RÉPLICA, PowerShell, cualquier carpeta. Solo lectura/preparación.**
```powershell
docker info --format '{{.Swarm.NodeID}}'
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
$targetContainer = Read-Host 'Container ID del backend transformers en ESTE worker'
docker inspect --format 'Service={{index .Config.Labels "com.docker.swarm.service.name"}} Task={{index .Config.Labels "com.docker.swarm.task.id"}} Health={{.State.Health.Status}}' $targetContainer
```

Copiar el ID del contenedor mostrado en **ese PC**. Debe decir `Service=transformers_backend`, una TaskID y `Health=healthy`. Enviar a Sofía **NodeID y TaskID**, sin secretos. No escribir `docker kill` todavía.

Si no aparece ningún contenedor, el backend no está en ese worker: resolver placement antes; no elegir otro contenedor. Si hubo despliegue/reinicio desde esta preparación, volver a obtener el ID antes del kill. El recorrido coloca la repetición de deploy **después** de availability para no invalidar esta selección.

## 3.4 M1 — CONTROL: guardar la correspondencia física

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; Vanessa ya tiene su NodeID visible en W1.**
```bash
docker info --format '{{.Swarm.NodeID}}'
docker node ls
docker service ps --filter desired-state=running --no-trunc transformers_backend
for task in $(docker service ps --filter desired-state=running -q transformers_backend); do
  docker inspect --type task --format 'Task={{.ID}} Node={{.NodeID}} Container={{.Status.ContainerStatus.ContainerID}}' "$task"
done
```

Anotar en la hoja de §12: “NodeID de Sofía → Mac físico” y “NodeID de Vanessa → PC físico”. Los dos hostnames pueden llamarse `docker-desktop`; por eso se usan IDs. Tomar captura **antes** de empezar. Con esto ya no se investiga “qué PC es cuál” durante la exposición.

## 3.5 Archivos/pestañas de resultados listos

En M3 dejar Finder abierto a la raíz del repo y editor visible. Cuando el ejecutor imprima `Evidencia: ...`, copiar esa ruta o abrir desde Finder `artifacts → quality → carpeta con fecha/PID`. Cada ejecución genera una carpeta nueva; no mezclar la de ensayo con la de exposición.

Para ver la carpeta en Finder sin navegar a mano:

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; cuando artifacts/quality ya exista por una corrida del ejecutor.**
```bash
open artifacts/quality
```

El ejecutor guarda el log de k6 mientras corre y lo imprime al terminar cada corrida. **Una terminal sin el resumen inmediato no significa que esté congelada.** El avance se puede leer abriendo el `.log` de la carpeta anunciada.

# 4. Minuto 00–01 — mostrar que el sistema está abierto

**Pantalla:** GUI FUNCIONAL. **Persona:** Vanessa o responsable funcional. **Lo que ya está listo:** sistema arrancado, navegador y health abiertos.

1. Mostrar la ventana del launcher con “Marketplace está listo” **si se ejecutó realmente en Windows**. No hacer doble clic otra vez: recrea backend/frontend y prepara datos demo.
2. Mostrar navegador en `http://localhost:4300` de ese Windows. Si la demo funcional usa otro entorno preparado, mostrar su URL real y nombrarlo.
3. Mostrar pestaña de `/healthz` y `/api/actuator/health/readiness` de ese mismo frontend.
4. Volver a GUI funcional. Tomar captura `01-sistema-listo.png`.

**Decir:** “El frontend se comunica con el backend y la base de datos. Este entorno funcional ya está iniciado; las pruebas distribuidas las mostraremos en el stack Swarm identificado”.

**Pasar al siguiente paso:** al verse GUI y health UP. No gastar este minuto explicando instalaciones ni la arquitectura entera.

🔴 **Si el launcher no abre:** usar el frontend ya preparado que sí responde, identificarlo y seguir. El diagnóstico de launcher está en §13.1. No presentar un artifact construido como doble clic exitoso.

# 5. Minuto 01–05 — demostración funcional asignada

**Pantalla:** GUI FUNCIONAL. **Persona:** responsable de cada flujo.

1. Entrar con la cuenta preparada para el flujo.
2. Ejecutar los flujos asignados según sus guías individuales.
3. Mostrar resultado, refrescar y comprobar persistencia cuando corresponda.
4. Tomar captura del resultado antes de cambiar a Sofía.
5. No tocar Docker, no relanzar el exe y no ejecutar seeds.

**Para el paso exacto de cada CU, utilizar las guías individuales:** [CU-08 y CU-11](cu-08-autenticacion-cu-11-cancelacion.md). Este documento no cambia ni sustituye esas instrucciones.

**Decir al cerrar:** “Este resultado se conserva al consultar nuevamente. Pasamos a las pruebas y a las mediciones de atributos de calidad”.

# 6. Minuto 05–07 — integración y coverage

**Pantalla:** reporte ya abierto en M3. **Persona:** Sofía. **No ejecutar una suite limpia en este bloque.**

1. Mostrar qué suite produjo el reporte y a qué versión corresponde.
2. Mostrar resumen de tests: ejecutados, fallos, errores y omitidos.
3. Mostrar contador **LINE**: covered, missed, total y porcentaje.
4. Mostrar el gate. El perfil `integration-coverage` exige **cero líneas missed**; el gate normal de CI es **95 %** y no es la misma prueba.
5. Guardar `02-coverage.png`. Continuar a performance.

**Lo que sabemos y no se debe exagerar:**

| Elemento | Evidencia disponible |
|---|---|
| Requisito del profesor | 100 % integración backend |
| Histórico documentado | 80 clases, 864 tests, 6811 líneas cubiertas/7006, 195 missed: **97,216671 %**; gate fallido |
| Manifiesto auditado | 81 clases concretas de integración |
| Porcentaje de la versión actual | No medido nuevamente en esta tarea; no atribuirle el histórico |
| Cambios de atributos de calidad | No alteraron CUs, pruebas funcionales ni coverage |

**Si solo está disponible el histórico, decir literalmente:** “El requisito es 100 %. El último resultado documentado fue 97,216671 % y no pasó el gate estricto. La suite actual tiene 81 clases; no estamos presentando ese porcentaje histórico como una medición nueva”.

Si el HTML no existe, mostrar [documentación histórica de integración](../pruebas/cobertura-integracion-backend.md). **No abrir una clase al 100 % y presentarla como el backend completo.** La receta para generar el reporte antes está en §14.6.

# 7. Minuto 07–11 — performance: un comando para ambas cargas

**Pantalla:** M2 — PRUEBAS. **Persona:** Sofía. La cuenta y `QUALITY_URL` ya están cargadas desde §3.2.

## 7.1 Ejecutar una sola vez

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager/cliente de carga, M2 — PRUEBAS, Bash, raíz del repositorio.**
```bash
python3 scripts/quality/run.py performance --base-url "$QUALITY_URL"
```

**No ejecutar otro comando en M2 hasta que termine.** No abrir otra prueba k6 al mismo tiempo.

El script hace esto, en orden:

1. Crea `artifacts/quality/<fechaUTC>-<PID>/` y muestra su ruta.
2. Guarda `context.json`: SHA local, estado del árbol de trabajo, versión de k6, URL y umbrales.
3. Valida cuenta/login/catálogo/detalle desde k6. **No registra cuentas ni crea productos.**
4. Ejecuta **50 VUs durante 1 min**; al finalizar imprime su salida.
5. Ejecuta **100 VUs durante 1 min**; al finalizar imprime su salida.
6. Conserva logs, resumen JSON y resultado de cada una; imprime `Medición PASS` o `Medición FAIL`.

⏱ Son dos minutos nominales de carga más preparación/login/cierre. Por eso reservamos cuatro minutos. El `gracefulStop` del escenario es de 10 s; no prometer finalización al segundo exacto.

**Mientras corre la primera, decir:** “Medimos navegación de catálogo por el frontend, pasando por backend y MySQL. Primero 50 usuarios virtuales y luego 100, con el mismo entorno”.

## 7.2 Leer estos números, en este orden

| Nombre visible | Qué anotar | Criterio |
|---|---|---|
| `catalog_list_duration` | `p(95)` del listado | ≤3000 ms |
| `catalog_detail_duration` | `p(95)` del detalle | ≤3000 ms |
| `catalog_error_rate` | Rate de error de la prueba | <0,02, es decir <2 % |
| `catalog_throughput` | Count y rate por segundo | Al menos un recorrido exitoso; no se inventó tasa mínima ASR |
| `catalog_requests` | Total HTTP listado/detalle durante carga | Dato cuantitativo |
| `catalog_successes` / `catalog_failures` | Respuestas 200 / no 200 de esos requests | Suman catalog_requests |
| `http_reqs` | Total HTTP y req/s, incluyendo preparación | Tiene denominador distinto al de catálogo |

El rate de negocio también detecta fallos de login/catálogo vacío; no es idéntico a contar respuestas HTTP fallidas. Las VUs usan cookie jars separados, pero la misma cuenta configurada: **100 VUs no significa 100 cuentas distintas**.

🟢 **PASS:** se completó cada corrida, existen muestras de listado/detalle y se cumplen thresholds. 🔴 **FAIL:** guardar resultado y decir el valor incumplido; no cambiar `P95_LIMIT_MS` ni `ERROR_RATE_LIMIT`. El ejecutor elimina overrides heredados de esos dos umbrales.

## 7.3 Mostrar los archivos sin buscarlos por todo el repo

1. En M3 abrir la carpeta que imprimió `Evidencia:`.
2. Abrir `catalogo-50-result.json` y después `catalogo-100-result.json` en el editor.
3. Mostrar `vus`, `k6_exit_code`, `metrics` y `measurement_passed`.
4. Para el resumen completo, abrir `catalogo-50.json` / `catalogo-100.json` o leer la consola.
5. Capturar `03-performance-50.png` y `04-performance-100.png`; anotar resultados en §12.

**Decir al terminar:** “Con 50 VUs obtuvimos [valores] y con 100 [valores]. El resultado de los umbrales fue [PASS/FAIL]. Conservamos las dos corridas, no solo una captura”.

**Transición:** “Ahora mantenemos tráfico mientras detenemos una réplica del backend”. Dejar M2 abierta con las credenciales; el siguiente comando usa la misma ventana.

🔴 Si falla inmediatamente: cuenta/URL/catálogo no preparados, no prueba de rendimiento válida. Guardar log, declarar prueba no completada y pasar. Diagnóstico en §13.2.

# 8. Minuto 11–15 — availability: tráfico, una caída y recuperación

**Pantallas:** M2 medición, W1 réplica, M1 control. **Personas:** Sofía dirige; Vanessa ejecuta **un solo kill cuando Sofía lo indique**.

## 8.1 Iniciar tráfico y observación

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M2 — PRUEBAS, Bash, raíz; después de que performance haya terminado.**
```bash
python3 scripts/quality/run.py availability --base-url "$QUALITY_URL" --stack transformers
```

🟢 El ejecutor exige dos backend Running en nodos distintos y readiness UP antes de lanzar k6. Abre otra carpeta de evidencia, inicia **50 VUs durante 3 min** y muestra muestras con tiempos, tasks y readiness.

**IMPORTANTE:** ver muestras del observador no basta para saber que el login k6 ya pasó. En M3 abrir `availability.log` en la carpeta anunciada; confirmar que no hay error de setup y que empezó el escenario de carga. Esperar aproximadamente 30 s de tráfico estable antes de ordenar kill.

**Sofía dice:** “La prueba de tráfico está en ejecución. Vamos a detener únicamente la réplica que está en el computador de Vanessa”.

## 8.2 Mostrar las dos réplicas antes de la caída

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; M2 continúa ejecutándose.**
```bash
docker stack services transformers
docker service ps --filter desired-state=running --no-trunc transformers_backend
```

Mostrar 2/2 y los nodos ya asociados a ambos PCs. 📸 `05-replicas-antes.png`. No ejecutar deploy ahora.

## 8.3 Vanessa detiene UNA réplica en W1

Antes del kill, el container preparado debe seguir siendo el mismo backend del worker. Esta comprobación es de seguridad de la acción, no una investigación del computador.

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, PC worker Windows, W1 — RÉPLICA, PowerShell, cualquier carpeta; variable $targetContainer preparada en §3.3.**
```powershell
docker inspect --format 'Service={{index .Config.Labels "com.docker.swarm.service.name"}} Task={{index .Config.Labels "com.docker.swarm.task.id"}} Running={{.State.Running}}' $targetContainer
```

🟢 Debe indicar `transformers_backend` y `Running=true`. Si el ID ya no existe, no matar otro a ciegas: obtener el ID actual mediante §3.3. Si no se puede identificar con seguridad, dejar la prueba sin caída y explicar que no acredita recuperación.

**Sofía anuncia “ahora” y activa cronómetro. Vanessa ejecuta el bloque siguiente una sola vez.**

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, MISMO worker, W1 — RÉPLICA, PowerShell; solo tras la orden de Sofía y la comprobación anterior. INTERRUMPE ESA réplica backend.**
```powershell
(Get-Date).ToUniversalTime().ToString('o')
docker kill $targetContainer
```

🟢 Docker devuelve el ID detenido. **No volver a ejecutar, no matar MySQL y no cerrar Docker Desktop.** El contenedor de otro PC no se mata desde este worker por copiar su ID.

## 8.4 Sofía muestra el reemplazo

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; repetir este bloque mientras M2 mantiene carga.**
```bash
docker service ps --no-trunc transformers_backend
docker stack services transformers
curl --fail --max-time 5 "$QUALITY_URL/api/actuator/health/readiness"
```

🟢 Esperado: task anterior Failed/Shutdown, task nueva y retorno a 2/2. Puede no alcanzarse a capturar 1/2 por la frecuencia de consulta; no inventar esa captura. Guardar `06-task-caida.png` y `07-task-nueva.png`.

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, worker Windows, W1 — RÉPLICA, PowerShell; lectura de la task NUEVA, sin volver a matar.**
```powershell
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
```

Debe aparecer nuevo contenedor y posteriormente healthy. Sofía comprueba también la réplica local:

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager, M1 — CONTROL, Bash, raíz.**
```bash
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
```

No confundir `Running` con `healthy`. Anotar en cronómetro cuándo se observan dos contenedores healthy y readiness UP. No se promete recuperación en diez segundos: la política espera 10 s y luego está el arranque.

## 8.5 Dejar terminar M2 y abrir UN resultado

1. **No interrumpir k6 con Ctrl+C.** Dejar terminar sus 3 minutos nominales.
2. En M3 abrir `availability-result.json` dentro de la carpeta que imprimió M2.
3. Mostrar `k6_exit_code`, métricas y `recovery`.
4. Anotar `observed_recovery_seconds`, `failure_observed`, `replacement_observed`, `invalid_reason` y `measurement_passed`.
5. Guardar `08-availability.png` junto a `availability-samples.jsonl`, `availability.json`, `.log` y `-result.json`.

**Qué significa el tiempo automático:** primera muestra que detecta ausencia de una task original → tercera muestra consecutiva con dos tasks Running en nodos distintos, una task nueva y readiness proxy UP. Sondeos cada ~2 s más latencia. **No es el instante exacto del kill ni la salud individual de ambos contenedores.** El cronómetro/capturas complementan esa medida.

El script da FAIL si no observa pérdida/reemplazo, si desaparece también la otra task original o si falla k6. El JSON conserva `physical_computers_verified: false`: relacionar NodeIDs con fotos/terminales físicas sigue siendo evidencia humana, no inferida por el programa.

**Frase de cierre:** “En esta corrida hubo [requests], [fallos], P95 [valores] y recuperación observada de [segundos]. Swarm reemplazó esta task después de detenerla en el worker. Probamos caída de un backend; MySQL continúa siendo una sola réplica”.

🔴 Si no recupera: guardar FAIL, no matar más contenedores y pasar a explicar el estado con §13.3. **No decir cero errores si la salida no lo dice.**

# 9. Minuto 15–18 — desplegabilidad y un único script

**Pantalla:** M1 — CONTROL. **Persona:** Sofía. Performance y availability ya terminaron.

## 9.1 Mostrar dos computadores y servicios

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager, M1 — CONTROL, Bash, raíz.**
```bash
docker node ls
docker stack services transformers
docker service ps --filter desired-state=running --no-trunc transformers_frontend
docker service ps --filter desired-state=running --no-trunc transformers_backend
docker service ps --filter desired-state=running --no-trunc transformers_mysql
```

Señalar los IDs físicos preparados en §3.4. 🟢 Frontend 2/2, backend 2/2, mysql 1/1. Una réplica frontend/backend por nodo; MySQL fijada a manager y volumen local.

**Código actual:** máximo una réplica por nodo en despliegue real. `deploy.sh` exige al menos dos nodos Linux Ready/Active antes de desplegar y verifica distribución al converger. `--local` permite dos en el mismo nodo, **solo para ensayo**. Las actualizaciones son una por vez con `stop-first` para no necesitar un tercer slot en dos nodos.

## 9.2 Mostrar el script y repetir el arranque preparado

En el editor, mostrar `deploy.sh` y `stack.yml`. Explicar que cluster, credenciales, imágenes y puertos son preparación previa. El script inicia los tres servicios y comprueba convergencia/readiness.

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz, MISMAS variables y tags preparados en §3.1; solo después de terminar ambas pruebas de carga.**
```bash
bash deploy.sh
```

No usar `--local` para la prueba de dos computadores. No cambiar tag, claves ni nodo de MySQL durante esta repetición.

🟢 Esperado: reutiliza secrets, despliega frontend/backend/mysql y termina con “Frontend y API disponibles; réplicas convergidas y readiness accesible...”. Exige tres ciclos UP. 📸 `09-deploy-script.png`.

No prometer terminar en tres minutos si el registry/red está lento: deploy siempre hace pull en modo real y la convergencia tiene timeout de 600 s después de preparación. Si sigue en curso al minuto 18, dejarlo en M1, explicar estado actual y pasar al navegador. No llamar “éxito” a una operación no terminada.

**Decir:** “Desde este manager ejecutamos un único script para desplegar los tres servicios. Aquí está la distribución entre dos computadores. MySQL mantiene una sola réplica; no estamos afirmando alta disponibilidad de base de datos”.

🔴 Si solo hay un nodo: el script ahora rechaza el despliegue real. Mostrar ensayo parcial y decir claramente que **no cumple dos computadores**. No retirar el cluster para intentar recrearlo durante la exposición.

# 10. Minuto 18–20 — CI/CD, GHCR y launcher

**Pantalla:** pestañas GitHub ya abiertas en M3. **Persona:** Sofía. No hacer push ni disparar una ejecución nueva para esta explicación.

## 10.1 Actions: leer el estado que aparece

1. Abrir **Backend CI and GHCR** y la ejecución del commit elegido.
2. Señalar **SHA**; no mostrar un verde de otro commit como resultado de los cambios locales.
3. Mostrar `Maven verification`: Java 21, Docker/Testcontainers, Maven y artifacts de coverage/tests.
4. Mostrar `Frontend E2E with Playwright`: Node 22, npm, build, Chrome y E2E real existente.
5. En una ejecución que **ya contenga estos cambios**, mostrar `Quality attribute tooling checks`: sintaxis deploy, tests del medidor y render stack. Son tests de herramientas, no ASR del sistema real.
6. Mostrar publicación backend/frontend a GHCR: tags `latest` y `sha-<SHA completo>`, plataformas amd64/arm64.
7. Mostrar `Deploy to Swarm (self-hosted)` con su estado real. Si está queued o ausente, decirlo.

**Dependencias del workflow local actualizado:** validate + frontend-e2e + quality-checks → publish → deploy. Antes publish solo esperaba validate; no describir una ejecución antigua con el comportamiento nuevo.

**CD necesita:** runner con labels `self-hosted, swarm-manager`, environment `production`, Docker/Bash/curl y GHCR login preconfigurado; secrets existentes o archivos de claves disponibles en el runner. Un YAML no crea ese computador ni conecta su runner.

## 10.2 GHCR: dos imágenes y su versión

Abrir Packages backend y frontend. Mostrar el tag SHA de cada uno y su digest/plataforma. No asumir que latest es el mismo commit. Si el tag no existe, no afirmar que ese commit fue desplegado.

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; SOLO si el deploy anterior terminó y la terminal ya está libre.**
```bash
docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' transformers_backend
docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' transformers_frontend
```

Si M1 sigue ocupada, mostrar la captura de estas referencias tomada en preparación; no interrumpir deploy para pegar otro comando.

## 10.3 Launcher: qué sí acredita

Abrir **Build Marketplace launcher** → job `launcher` → artifact **Marketplace-win-x64**. Mostrar Windows runner, .NET 8, self-contained, win-x64, single file.

**Decir:** “Este workflow construye el ejecutable Windows y entrega el artifact. La ejecución por doble clic se acredita en el computador donde lo abrimos; no por el build solamente”.

📸 `10-ci-cd.png`, `11-ghcr.png`, `12-launcher-build.png`. Finalizar guardando §12, sin prometer un pipeline verde si hay jobs pendientes.

# 11. Lo que NO se debe decir por accidente

| Evitar | Decir lo que realmente se observa |
|---|---|
| “Dos réplicas significa dos PCs” | “Estas tasks tienen NodeIDs distintos, asociados a estos computadores” |
| “Ya está todo al 100 %” | Mostrar porcentaje de integración y alcance de su reporte |
| “El exe despliega Swarm” | “El exe usa Compose local; deploy.sh despliega Swarm” |
| “No hubo errores” | Leer el contador/rate real y su denominador |
| “Recovery es exactamente este tiempo desde kill” | Distinguir sondeo automático y cronómetro desde kill |
| “La DB es altamente disponible” | MySQL tiene una réplica y volumen local |
| “Esta ejecución antigua prueba el CI nuevo” | Identificar SHA y jobs presentes en esa ejecución |
| “Health UP significa que todos los CUs funcionan” | Health no reemplaza pruebas funcionales |
| “Los 11 tests prueban performance real” | Validan el medidor con escenarios simulados, no el ASR |

# 12. Hoja para llenar y capturas — mantener a mano

| Dato | Resultado de ESTA demostración |
|---|---|
| Fecha/hora | |
| SHA local y cambios sin commit | |
| Tag/digest backend y frontend desplegados | |
| Stack y URL de carga | |
| Sofía → NodeID / IP | |
| Vanessa → NodeID / IP | |
| Frontend/backend/MySQL réplicas | |
| Dataset medido: productos/cuentas/pedidos conocidos | |
| 50 VUs: P95 listado / detalle | |
| 50 VUs: error rate / throughput | |
| 50 VUs: requests / successes / failures / PASS-FAIL | |
| 100 VUs: P95 listado / detalle | |
| 100 VUs: error rate / throughput | |
| 100 VUs: requests / successes / failures / PASS-FAIL | |
| Availability: total HTTP / catálogo y fallos | |
| Availability: P95 / throughput | |
| Task anterior / nueva / nodo | |
| Recovery automático por sondeo | |
| Tiempo manual desde kill / observación healthy | |
| Coverage: versión, porcentaje, tests y gate | |
| CI run y estado / CD estado | |
| Carpeta de evidencia performance | |
| Carpeta de evidencia availability | |
| Fallo o limitación declarada | |

**Capturas en orden:** 01 sistema, 02 coverage, 03 carga 50, 04 carga 100, 05 replicas antes, 06 caída, 07 nueva task, 08 availability, 09 script, 10 CI/CD, 11 GHCR, 12 launcher build. Añadir captura previa de NodeIDs en los dos PCs. Guardarlas en `evidencias-sustentacion/`; los JSON/logs automáticos quedan en `artifacts/quality/`.

En Mac, usar captura de área/ventana del sistema; en Windows, Recortes. Evitar incluir otras pestañas privadas, tokens, `.env` o contraseñas. **Los nombres son archivos por generar, no evidencia ya existente.**

# 13. Emergencias — leer SOLO el caso que esté fallando

## 13.1 Launcher Windows

| Síntoma | Acción breve |
|---|---|
| SmartScreen | Verificar procedencia del exe. Si política permite, Más información → Ejecutar de todas formas. No desactivar protección global |
| No ocurre nada | Abrir desde PowerShell en carpeta de entrega; verificar que no siga abierta otra instancia |
| Docker apagado | Abrir Desktop, esperar motor, cerrar ventana anterior y reintentar fuera del flujo |
| Windows containers | Cambiar a Linux containers antes de intentar demo |
| Faltan archivos | Restaurar entrega completa, no solo artifact exe |
| Error de contraseña MySQL | Conservar .env.demo y volumen; no regenerar claves a ciegas |
| Primera build sin Internet | Usar sistema previamente construido; no garantizar build offline |
| Dice listo pero no abre browser | Abrir http://localhost:4300 en ESE Windows |

Para abrir PowerShell en la carpeta: Explorador → carpeta donde está Marketplace.exe → barra de dirección → escribir `powershell` → Enter.

**DÓNDE EJECUTAR ESTO — 💻 PC WINDOWS del launcher, PowerShell, raíz de la entrega; solo para ARRANCAR si aún no se está demostrando un flujo.**
```powershell
.\Marketplace.exe
```

**DÓNDE EJECUTAR ESTO — 💻 MISMO Windows, otra PowerShell, raíz de la entrega; diagnóstico.**
```powershell
Get-Content .\launcher-logs\marketplace.log -Tail 80
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml ps
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml logs --tail 80 backend frontend mysql
curl.exe --fail --max-time 5 http://localhost:4300/healthz
curl.exe --fail --max-time 5 http://localhost:4300/api/actuator/health/readiness
```

El log se sobrescribe al abrir otra ejecución; guardar antes de reintentar. Si falla FindRoot antes de abrir log, puede no haber log nuevo. El launcher fija puertos 4300/8080/3307 y proyecto `marketplace-demo`; cambiar puertos en `.env.demo` no vence los valores forzados por el exe.

Cerrar ventana no detiene contenedores. Para cerrar el sistema al terminar todo, cerrar primero la ventana previa del launcher y abrir **Cerrar Marketplace.cmd**; usa stop y conserva datos. No hacerlo durante carga/flujos.

## 13.2 k6 no empieza o falla

1. Abrir `.log` en la carpeta anunciada por el ejecutor.
2. Login fallido: cuenta equivocada, no verificada o contraseña/entorno distintos. No modificar autenticación.
3. Catálogo vacío: no hay datos para detalle; no es performance válida. No seedear sobre datos importantes.
4. Conexión rechazada: revisar `QUALITY_URL` y health desde M1.
5. Error de thresholds: conservar valor real; no relajar umbral ni repetir hasta ocultar el FAIL.
6. Si CPU está saturada, detener solo pruebas/builds que el equipo haya iniciado para preparación y ya no necesite; no matar procesos desconocidos. Registrar condiciones de la corrida.

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; diagnóstico sin tocar servicios.**
```bash
curl --fail --max-time 5 "$QUALITY_URL/healthz"
curl --fail --max-time 5 "$QUALITY_URL/api/actuator/health/readiness"
docker stats --no-stream
```

## 13.3 Se cae el servicio tras matar una réplica

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz.**
```bash
docker node ls
docker stack ps --no-trunc transformers
docker service logs --tail 80 transformers_frontend
docker service logs --tail 80 transformers_backend
docker service logs --tail 80 transformers_mysql
```

Revisar: worker desconectado, frontend apuntando a otro entorno, red overlay, dependencia MySQL o backend reiniciando. No matar una segunda réplica ni reiniciar Desktop. Si no vuelve, conservar FAIL y explicar la limitación; un solo fallo de backend no debería usarse para afirmar HA de DB.

## 13.4 Frontend/backend/MySQL no arrancan

| Componente | Orden de lectura | Evitar |
|---|---|---|
| Frontend | healthz → readiness proxy → service ps/logs frontend | Cambiar proxy durante prueba; confundir caché browser con imagen nueva |
| Backend | service ps → logs backend → salud MySQL/credenciales/Flyway → recursos | Reparar migraciones o fixtures a mano |
| MySQL | placement en manager → logs → estado volumen/espacio → credenciales originales | Cambiar MYSQL_NODE_ID o borrar volumen |

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz; stack transformers preparado.**
```bash
docker service ps --no-trunc transformers_mysql
docker service inspect --format '{{json .Spec.TaskTemplate.Placement.Constraints}}' transformers_mysql
docker volume ls
docker system df
```

Healthchecks reales: frontend `/healthz`; backend imagen `/actuator/health/liveness`; readiness backend `/actuator/health/readiness` incluye DB; proxy frontend `/api/actuator/health/readiness`. MySQL hace SELECT 1 con usuario/base de aplicación. MySQL Swarm no responde por 3307 porque no publica puerto.

## 13.5 Puerto ocupado

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac, M1 — CONTROL, Bash, raíz; diagnóstico previo o emergencia.**
```bash
lsof -nP -iTCP:18000 -sTCP:LISTEN
lsof -nP -iTCP:18090 -sTCP:LISTEN
docker ps --format '{{.Names}}\t{{.Ports}}'
docker service ls
```

**DÓNDE EJECUTAR ESTO — 💻 PC Windows del launcher, PowerShell, cualquier carpeta.**
```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 4300,8080,3307,18000,18090 } | Select-Object LocalAddress,LocalPort,OwningProcess
docker ps --format '{{.Names}}\t{{.Ports}}'
```

No terminar PID de Docker Desktop para liberar un puerto. Identificar instalación propietaria y acordar otro entorno/puerto **antes**; todas las URLs deben coincidir con esa configuración.

## 13.6 Internet o Swarm fallan

- **Internet cae, LAN sigue:** contenedores locales y k6 ya instalados pueden continuar. CD/GHCR no se pueden demostrar en línea; usar capturas identificadas. Deploy real vuelve a hacer pulls y puede fallar.
- **LAN cae:** overlay y DB remota pueden quedar inaccesibles. Mostrar demo Compose local preparada; declarar que no se está acreditando distribución física.
- **GitHub no carga:** mostrar capturas con SHA/fecha/run, no llamarlas corrida en vivo.
- **Solo hay un nodo:** no forzar `--local` como si cumpliera. Mostrar reconciliación local, si ya está preparada, como evidencia parcial.
- **Sin evidencia previa:** decir “no ejecutado/no disponible”; no inventar JSON, porcentajes ni capturas.

**Lista roja:** nada de prune, down -v, borrar `.env.demo`, borrar volumen, leave --force, quitar secrets, cambiar thresholds, matar MySQL, migraciones manuales, hacer push ni editar CUs para salvar la presentación.

## 13.7 Variante del worker si Vanessa usa macOS/Linux

Preparar esta terminal una vez, antes del reloj; durante availability usarla en lugar de los bloques PowerShell, sin cambiar el recorrido de Sofía.

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, worker macOS/Linux, W1 — RÉPLICA, Bash, cualquier carpeta; PREPARACIÓN.**
```bash
docker info --format '{{.Swarm.NodeID}}'
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
read -r -p 'Container ID backend de ESTE worker: ' TARGET_CONTAINER_ID
docker inspect --format 'Service={{index .Config.Labels "com.docker.swarm.service.name"}} Task={{index .Config.Labels "com.docker.swarm.task.id"}} Health={{.State.Health.Status}}' "$TARGET_CONTAINER_ID"
```

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, MISMA W1 Bash del worker, cualquier carpeta; durante §8.3, SOLO cuando Sofía indique kill y el ID siga siendo la réplica elegida.**
```bash
date -u '+T0 kill %Y-%m-%dT%H:%M:%SZ'
docker kill "$TARGET_CONTAINER_ID"
```

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, MISMA W1 Bash del worker, después; lectura de la nueva réplica.**
```bash
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
```

El `.exe` Windows no se ejecuta nativamente en este worker; usar GUI preparada y artifact build como tales.

# 14. Preparación del entorno desde cero — FUERA de los 20 minutos

Esta sección no es parte del guion proyectado. **No ejecutar init/leave sobre un cluster existente para “empezar limpio”.** El Mac auditado ya tenía uno con datos. No se ha corregido automáticamente su red.

## 14.1 Verificar IP una vez y anotarla

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac manager candidato, Terminal, cualquier carpeta.**
```bash
networksetup -listallhardwareports
route -n get default
ifconfig
```

Identificar Wi-Fi/Ethernet activo. En la auditoría `en0` tenía `192.168.40.13`; no elegir loopback, utun/VPN ni bridge de VM. Anotar la IP actual. Si en0 sigue siendo la interfaz correcta:

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac, Terminal, cualquier carpeta; interfaz en0 ya identificada.**
```bash
ipconfig getifaddr en0
```

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, Windows, PowerShell, cualquier carpeta; identificar su interfaz física y alcanzar candidata de Sofía.**
```powershell
Get-NetIPConfiguration
Get-NetConnectionProfile
ping -n 3 192.168.40.13
Test-NetConnection 192.168.40.13 -Port 2377
```

Cambiar IP en ese bloque si cambió. 2377 solo tiene sentido con manager activo escuchando; ping bloqueado no prueba ausencia de red. Deben llegar entre motores TCP 2377, TCP/UDP 7946 y UDP 4789; además frontend/backend publicados hacia clientes. TCP abierto no demuestra overlay UDP.

**DÓNDE EJECUTAR ESTO — 💻 VANESSA, worker macOS/Linux, Terminal, cualquier carpeta; alternativa a la prueba Windows.**
```bash
ping -c 3 192.168.40.13
nc -vz -w 3 192.168.40.13 2377
nc -vz -w 3 192.168.40.13 7946
```

Mismo Wi-Fi puede aislar clientes. Docker Desktop ejecuta daemon en VM: IP del host no garantiza alcance de puertos del motor. La auditoría observó NodeAddr **192.168.65.3** en Desktop. Si esa red no alcanza al otro motor, resolver topología antes (motores Linux/VMs con red alcanzable); no hay comando mágico en el repo que convierta NAT en cluster físico funcional. Referencia: [red Swarm de Docker](https://docs.docker.com/engine/swarm/networking/).

## 14.2 Formar cluster sin destruir el existente

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, terminal local del motor elegido, cualquier carpeta; lectura.**
```text
docker context show
docker info --format '{{.OSType}} {{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}} {{.Swarm.NodeID}} {{.Swarm.NodeAddr}}'
```

`inactive`: disponible para init/join. `active true`: ya es manager. `active false`: ya es worker. Si está en el cluster correcto, conservarlo. Si está en otro, planificar preservación de cargas; no usar leave --force como receta de emergencia.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal del motor nuevo, cualquier carpeta; SOLO inactive, dirección confirmada y red alcanzable.**
```bash
docker swarm init --advertise-addr 192.168.40.13
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER activo, terminal privada, cualquier carpeta; no proyectar token.**
```bash
docker swarm join-token worker
```

Copiar el comando completo que emite Docker y ejecutarlo en el worker. Si anuncia dirección inaccesible, arreglar topología antes; cambiar texto no corrige overlay.

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER, terminal local del motor elegido, cualquier carpeta; plantilla: sustituir por el comando real emitido por manager.**
```text
docker swarm join --token REEMPLAZAR_TOKEN_REAL 192.168.40.13:2377
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, raíz del repo; worker unido, copiar su ID real en prompt.**
```bash
docker node ls
MANAGER_NODE_ID=$(docker info --format '{{.Swarm.NodeID}}')
read -r -p 'NodeID real del worker: ' WORKER_NODE_ID
docker node update --label-add backend_zone=pc1 "$MANAGER_NODE_ID"
docker node update --label-add backend_zone=pc2 "$WORKER_NODE_ID"
```

Ready = motor disponible; Active = acepta tasks; Leader = manager líder. Las etiquetas favorecen reparto y el máximo por nodo impide colocar las dos réplicas del mismo servicio juntas en modo real. Dos NodeIDs deben corresponder a dos PCs, no dos VMs en un único PC.

## 14.3 GHCR, secrets y primer deploy

En manager, entrar a repo y establecer las variables de §3.1 **sin exigir todavía stack services/health**. Elegir un tag realmente publicado para ambas arquitecturas.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash privada, raíz; preparación GHCR, sin proyectar credenciales.**
```bash
docker login ghcr.io
docker manifest inspect "ghcr.io/transformersas/transformers-as-backend:${BACKEND_IMAGE_TAG}"
docker manifest inspect "ghcr.io/transformersas/transformers-as-frontend:${FRONTEND_IMAGE_TAG}"
```

`denied`: revisar acceso al paquete. `manifest unknown`: tag no publicado. No cambiar silenciosamente a latest. `deploy.sh` transmite auth a agentes mediante `--with-registry-auth`; ambos nodos necesitan red al registry y arquitectura compatible. Predescargas manuales del worker privado requieren credenciales de lectura allí.

Para stack transformers se usan `transformers_db_password_v1`, `transformers_mysql_root_password_v1` y `transformers_logistics_webhook_secret_v1`. Si no existen, deploy pide las dos claves DB sin eco y genera webhook aleatorio; si existen, los reutiliza. Hacer primer arranque fuera de pantalla compartida. Guardar claves de forma segura y consistentes con volumen existente.

Para ejecución no interactiva: archivos fuera del repo, sin salto final, no vacíos; `DB_PASSWORD_SECRET_FILE`, `MYSQL_ROOT_PASSWORD_SECRET_FILE`, `LOGISTICS_WEBHOOK_SECRET_FILE` apuntan a rutas del manager/runner. No basta definir esas rutas en GitHub si archivos no existen en el runner. No proyectar contenido. Deploy no carga `.env`.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, M1 — CONTROL, Bash, raíz, después de configurar tags/puertos y cluster. Primer arranque, fuera de los 20 min.**
```bash
bash deploy.sh
```

Esperar convergencia y comprobar las pantallas §3.1. Si falla, conserva stack para diagnóstico; no borrar datos. Los cambios locales de scripts/stack deben estar disponibles en el manager; el cambio YAML de CI solo estará activo en GitHub después de publicarlo mediante el flujo autorizado, no por editarlo aquí.

## 14.4 Datos para pruebas

El Swarm no activa perfil demo. Se necesitan cuenta verificada y catálogo en su propia base. El exe prepara otra base Compose, por lo que sus cuentas no aparecen automáticamente en Swarm.

Si el dataset ya está preparado, conservarlo. Si no, usar fuera de clase [datos de demostración y rendimiento](../pruebas/datos-demostracion-y-rendimiento.md) sobre una base **desechable identificada y sin tráfico**. Los scripts existentes de CUs no se modificaron; no ejecutarlos sin conocer la base afectada. No cargar/resetear datos mientras k6 corre.

El generador performance existente admite 100 usuarios del pool, 1.000 productos y 10.000 pedidos sintéticos por defecto; incluye cuentas auxiliares. **No afirmar ese volumen si no se cargó y contó en la base medida.** Crear datos no demuestra el ASR. Cuenta verificada y al menos un producto bastan para arrancar el script, pero la escala del dataset debe declararse con honestidad.

## 14.5 Preparar launcher antes de la demo

En Windows descomprimir entrega completa y conservar exe, `Cerrar Marketplace.cmd`, ambos Compose, `frontend/` y `backend/demo/` con Dockerfiles y archivos ocultos de build. Abrir Desktop Linux; puertos 4300/8080/3307 libres; doble clic en exe una vez.

Secuencia real: verifica Docker/Compose → genera/conserva `.env.demo` → MySQL → build/recreación backend con perfil demo → build/recreación frontend → health frontend y readiness proxy → navegador 4300. Puede tardar varios minutos con Internet; límite Docker por etapa 45 min, esperas Compose de 300/300/180 s y HTTP final hasta 3 min. No confundir límites con duración esperada medida.

Logs en `launcher-logs/marketplace.log`. Base/usuario `marketplace_demo`, volumen `marketplace-demo_mysql_data`. No borrar `.env.demo` conservando volumen ni relanzar durante un flujo: prepara/restaura fixture demo. Pago/reembolso y otros proveedores del perfil son simulados; backend/MySQL reales.

## 14.6 Preparar reporte de integración, antes de pruebas de carga

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, Mac, Terminal dedicada de preparación, raíz; JDK 21, Docker y Python 3 disponibles. No concurrente con k6.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
bash backend/demo/scripts/run-integration-coverage.sh
```

Aunque tests pasen, verify falla si queda una línea missed. Guardar esa salida; no bajar gate. Después:

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, MISMA Terminal de preparación, raíz; después de finalizar suite, incluso si falló únicamente el gate.**
```bash
python3 backend/demo/scripts/integration-coverage-report.py
open backend/demo/target/site/jacoco/index.html
```

Verificar `Complete allowlisted suite: True`, reports `surefire-integration-reports`, datos `jacoco-integration.exec`, resumen tests y contador LINE. La suite normal reutiliza ruta HTML: la existencia del archivo no demuestra que sea integración exclusiva. Guardar SHA/consola/HTML juntos y no correr `clean` después sin copia.

# 15. Referencia auditada — contexto, no un paso de la demo

**Auditoría original:** main `770f3c6da98a1885b69bf43c606a60986b2468e6`, 22 septiembre 2026. Posteriormente se incorporó la guía en `d4a2b830903f7388697767783d6b8276cc76d9fe`; las modificaciones de herramientas/despliegue/CI se encuentran locales al editar esta guía. No se atribuye a esos cambios un resultado remoto no observado.

| Elemento observado en auditoría original | Resultado |
|---|---|
| Mac Sofía IP | en0 192.168.40.13; volver a confirmar al preparar |
| Docker Desktop | Linux ARM64, un nodo docker-desktop; NodeAddr 192.168.65.3 |
| Stack activo observado | marketplace, backend 2/2 y mysql 1/1; sin frontend como servicio Swarm |
| Imagen backend activa entonces | sha-7a3c93d93f72c5f44d2838bbe2918b871770c019; anterior a main auditada |
| Compose activo observado | transformers-as, frontend 4300, backend 18080, mysql 3307 |
| Contenedor frontend adicional | marketplace-frontend-evidence en 8082; no es servicio del stack |
| Launcher versionado | Marketplace.exe en raíz, 67.506.529 bytes (~64,38 MiB), Windows x86-64 |
| Coverage local auditado | Sin XML JaCoCo/reporte nuevo de integración |
| CI al cierre de auditoría | Maven y E2E success; publicación aún en curso; no se certificó CD |
| Runners del repositorio consultados | 0; runner organización/disponibilidad no verificados |
| Build launcher histórico | Success run 35715767674, SHA 856a52b312cef9d16fab8b83bf86f3b46725c13c |
| Validación posterior de herramientas | 11 tests simulados aprobados; no carga/kill/deploy reales |

| Configuración | Frontend | Backend | MySQL host |
|---|---|---|---|
| Compose defaults / launcher | 127.0.0.1:4300 | 127.0.0.1:8080 | 127.0.0.1:3307 |
| Compose observado en Mac | 127.0.0.1:4300 | 127.0.0.1:18080 | 127.0.0.1:3307 |
| Swarm defaults | ingress 80 | ingress 8080 | No publicado |
| Swarm --local defaults | ingress 18000 | ingress 18080 | No publicado |
| Recorrido de esta guía | ingress 18000 | ingress 18090 | No publicado |

**Scripts de carga existentes:** `scripts/k6/catalog-browse.js` y `scripts/k6/seller-register.js`. El primero acredita el escenario de catálogo con umbrales de 3 s / 2 % si la corrida real pasa; el segundo mide registro/escritura/BCrypt con umbral 4 s / 2 %, no sustituye el ASR de catálogo y no se ejecuta en el guion de 20 min. No se modificó el CU de registro.

**Workflow nuevo local:** `Backend CI and GHCR`, jobs validate, build, publish, frontend-e2e, quality-checks y deploy. `Build Marketplace launcher`, job launcher, sigue siendo construcción manual workflow_dispatch, no despliegue.

**Límites que permanecen:** una réplica MySQL, sin HA DB; rollback de imagen no revierte Flyway; un manager no ofrece redundancia del control; health no valida todos los flujos; cobertura 100 % no acreditada. El enunciado no enumera todos los componentes de CI/CD enseñados ni todos los atributos de clase y el SAD/SRS original no está disponible aquí: no se inventó cumplimiento de esos puntos.

Para detalle técnico de cambios y validaciones, [Pruebas de atributos de calidad](PRUEBAS-ATRIBUTOS-CALIDAD.md). Para operación en vivo, este documento ya contiene los comandos completos; no es necesario saltar al otro archivo.

# 16. Si solo puedes leer una página

**Todo abierto:** Sofía M1 CONTROL, M2 PRUEBAS con cuenta cargada, M3 resultados/GitHub; Vanessa W1 con backend identificado y GUI funcional lista. Entorno calidad transformers, frontend 18000 y backend 18090; QUALITY_URL preparado con IP real. No instalar, seedear ni formar cluster durante el reloj.

1. **00–05:** GUI y flujos asignados. Launcher ya listo; no abrirlo otra vez.
2. **05–07:** coverage preparado; mostrar porcentaje real y gate, no afirmar 100 %.
3. **07–11:** en M2 ejecutar performance; automáticamente 50 y 100 VUs. Guardar P95 listado/detalle, errores y throughput.
4. **11–15:** en M2 ejecutar availability; después de tráfico estable Vanessa mata UNA réplica en W1. Sofía muestra reemplazo en M1. Dejar terminar y abrir availability-result.json.
5. **15–18:** M1 muestra nodos/placement y repite deploy.sh con configuración ya preparada. No prometer éxito hasta convergencia.
6. **18–20:** Actions/GHCR/CD/launcher, SHA y estados reales. Guardar capturas.

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M2 — PRUEBAS, Bash, raíz, QUALITY_URL y cuenta exportadas en §3.2. PRIMERO performance; esperar a que termine.**
```bash
python3 scripts/quality/run.py performance --base-url "$QUALITY_URL"
```

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, MISMA M2, Bash, raíz, DESPUÉS performance. Coordinar kill en worker según §8, no improvisarlo desde manager.**
```bash
python3 scripts/quality/run.py availability --base-url "$QUALITY_URL" --stack transformers
```

**DÓNDE EJECUTAR ESTO — 💻 SOFÍA, M1 — CONTROL, Bash, raíz, DESPUÉS de terminar carga/availability, con tags/puertos/secrets ya preparados.**
```bash
docker node ls
docker stack services transformers
bash deploy.sh
```

**Si falla más de 90 s:** capturar, decir qué no se logró y continuar. **Nunca:** down -v, prune, leave --force, borrar claves/volúmenes, matar DB, cambiar thresholds, inventar resultados o modificar CUs.
