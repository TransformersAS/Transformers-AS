# Guía 101 — Sustentación técnica en vivo Transformers

**Rama auditada:** `main`. **SHA:** `770f3c6da98a1885b69bf43c606a60986b2468e6`.
**Fecha de inspección:** 22 de septiembre de 2026, 05:44–05:59, America/Bogota. Es una fotografía del estado, no una certificación permanente.
**Repositorio:** [TransformersAS/Transformers-AS](https://github.com/TransformersAS/Transformers-AS).
**Destino solicitado:** `docs/sustentacion/GUIA-SUSTENTACION-EN-VIVO.md`.

Esta auditoría solo leyó archivos, Git, GitHub, estado Docker y endpoints health; creó este documento. **No arrancó ni detuvo servicios, no mató réplicas, no cambió configuración, no cargó datos, no ejecutó suites, no hizo commit ni push.** Los comandos operativos siguientes son instrucciones para las expositoras: no son acciones realizadas durante la auditoría.

Convenciones: **OBSERVADO** = leído o consultado en esta auditoría; **HISTÓRICO** = afirmación de un documento o ejecución anterior identificada; 🟢 **ESPERADO** = lo que deberá verificarse al ejecutar; ⚠️ **NO VERIFICADO** = falta evidencia. Nunca convertir un resultado esperado en “ya probado”.

# 0. Resumen de emergencia

| Orden | Demo/prueba | Quién | Duración estimada | Resultado que debemos mostrar |
|---|---|---|---|---|
| 1 | Sistema ya preparado / launcher | Vanessa si tiene Windows | 1 min | Frontend abierto y health UP |
| 2 | Funcional general | Expositora de flujos | 4 min | GUI → backend → persistencia |
| 3 | Integración / cobertura | Sofía | 2 min | Alcance del reporte, porcentaje y gate honestos |
| 4 | Performance | Sofía, cliente de carga | 4 min | 50 y 100 VUs, ambos P95, errores, throughput |
| 5 | Availability | Sofía manager + Vanessa worker | 4 min | Una task detenida, tráfico, reemplazo y tiempo |
| 6 | Deployability | Sofía | 3 min | Dos nodos físicos, placement y script único |
| 7 | CI/CD / GHCR | Sofía, navegador | 2 min | Run del SHA, jobs reales, publicación y estado CD |

**No cabe construir todo ni formar el cluster desde cero dentro de esos 20 minutos.** Los pasos 2–8 son preparación previa. Si falla la preparación, elegir el plan B antes del cronómetro y declarar qué requisito queda pendiente.

## Fotografía que sí se observó

| Elemento | OBSERVADO en el Mac local |
|---|---|
| Git | `main`, árbol limpio al iniciar; `git ls-remote origin refs/heads/main` coincide con el SHA auditado |
| IP del Mac | `en0 = 192.168.40.13`; debe verificarse otra vez en el salón |
| Docker | Contexto `desktop-linux`, motor Linux ARM64, Swarm activo |
| Nodo | Solo `docker-desktop`, ID `v44u2a7k2ad8owj1ecij7b459`, Ready / Active / Leader |
| Dirección anunciada por Swarm | `192.168.65.3`, interna de Docker Desktop; no es la IP LAN del Mac |
| Stack activo REAL | `marketplace`, **2 servicios**, no `transformers` |
| Servicios activos | `marketplace_backend` 2/2; `marketplace_mysql` 1/1; ambos backends en el mismo nodo |
| Imagen backend activa | `ghcr.io/transformersas/transformers-as-backend:sha-7a3c93d93f72c5f44d2838bbe2918b871770c019`, anterior a main |
| Frontend Swarm activo | **No existe como servicio del stack observado** |
| Frontend separado | Contenedor `marketplace-frontend-evidence`, puerto host 8082; no cuenta como réplica Swarm |
| Compose activo | Proyecto `transformers-as`: frontend 4300, backend **18080**, MySQL 3307, todos healthy |
| Compose del launcher | Proyecto distinto: `marketplace-demo`; no observado activo |
| Health consultado | UP en 4300 `/healthz`, 4300 `/api/actuator/health/readiness`, 8080 y 18080 `/actuator/health/readiness` |
| Coverage actual | No existe `backend/demo/target/site/jacoco/jacoco.xml`; cero reportes locales `surefire-integration-reports/TEST-*.xml` |
| Suite actual | Auditor de manifiesto: **81 clases concretas de integración** |
| Launcher | `Marketplace.exe` versionado en raíz: **67.506.529 bytes**, ≈64,38 MiB, PE32+ Windows x86-64 |

**Conclusión operativa:** lo que responde hoy en el Mac mezcla Compose y un Swarm anterior. **No acredita main desplegada ni dos computadores.** No tocar ese stack para “hacerlo coincidir” durante esta auditoría.

## Puertos: tres cosas diferentes

| Entorno | Frontend host → contenedor | Backend host → contenedor | MySQL host → contenedor |
|---|---|---|---|
| `compose.yaml`, defaults | 127.0.0.1:4300 → 80 | 127.0.0.1:8080 → 8080 | 127.0.0.1:3307 → 3306 |
| Launcher, valores forzados | 127.0.0.1:4300 → 80 | 127.0.0.1:8080 → 8080 | 127.0.0.1:3307 → 3306 |
| Compose OBSERVADO en Mac | 127.0.0.1:4300 → 80 | 127.0.0.1:**18080** → 8080 | 127.0.0.1:3307 → 3306 |
| Swarm `deploy.sh`, defaults | ingress 80 → 80 | ingress 8080 → 8080 | Sin publicación; interno 3306 |
| Swarm `deploy.sh --local`, defaults | ingress 18000 → 80 | ingress **18080** → 8080 | Sin publicación |
| Swarm OBSERVADO `marketplace` | Ninguno | ingress 8080 → 8080 | Sin publicación |
| Receta propuesta de esta guía, aún NO ejecutada | ingress **18000** → 80 | ingress **18090** → 8080 | Sin publicación |

La receta usa variables soportadas para no colisionar con 8080 y 18080 ya ocupados. **18090 es una elección explícita de esta guía, no el default de `--local`.** Antes de adoptarla, comprobar ambos puertos. Lanzar el exe en el Mac no es posible y lanzarlo sobre el mismo motor que Compose activo chocaría en 4300/3307 y posiblemente 8080.

# 1. Roles físicos durante la sustentación

**PC 1 — Sofía / manager candidate: `192.168.40.13`**, confirmada solo en la inspección del Mac. **PC 2 — Vanessa / worker candidate:** sistema operativo e IP no verificados. Si Vanessa usa macOS/Linux, no intentar ejecutar el `.exe`; usar el entorno Docker preparado y demostrar el artifact Windows como evidencia de construcción.

| Acción | PC Manager | PC Worker |
|---|---|---|
| Verificar IP, Docker, arquitectura | Sí, localmente | Sí, localmente |
| Inicializar cluster nuevo | Solo manager y solo inactive | No |
| Unirse al cluster | Obtiene comando privado | Ejecuta join |
| Labels / node ls / stack / deploy.sh | Sí | No |
| Descargar imágenes | Sí | Sí, para comprobar arquitectura/acceso |
| Secrets | Los crea/reutiliza deploy.sh | Los recibe en tasks autorizadas |
| Identificar task que se matará | Localiza nodo / task ID | Confirma contenedor local |
| Matar UNA réplica | Solo si está en este nodo | Preferido si allí está la réplica elegida |
| k6 | Una terminal separada; mantenerla libre | Alternativa como cliente si se acordó |
| Navegador | Swarm y GitHub | Demo funcional / launcher Windows |

**Contextos de terminal:** los bloques Bash se ejecutan en Terminal de macOS, Bash de Linux o **Git Bash en Windows**. PowerShell se identifica por separado. No pegar `export`, `read -s` ni sustituciones Bash en PowerShell. En Windows, Git Bash debe hablar con el mismo Docker Engine que se comprobó; no mezclar por accidente Docker Desktop y un daemon independiente de WSL.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Mac de Sofía, Terminal; entrar a raíz del repositorio.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
pwd
git rev-parse HEAD
```

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER Windows, PowerShell; sustituir la ruta marcada por la carpeta REAL descomprimida/clonada.**
```powershell
Set-Location 'C:\REEMPLAZAR_RUTA_REAL\Transformers-AS'
Get-Location
git rev-parse HEAD
```

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER macOS/Linux, Terminal; sustituir ruta por la real.**
```bash
cd '/REEMPLAZAR_RUTA_REAL/Transformers-AS'
pwd
git rev-parse HEAD
```

No continuar con placeholders sin reemplazar. 🟢 Esperado: SHA de entrega. 🔴 Si difiere, registrar la diferencia; no actualizar a ciegas ni hacer pull durante la exposición.

# 2. Checklist PRE-SUSTENTACIÓN — 5 minutos antes

Estas son comprobaciones finales de cinco minutos **si las descargas, builds, suite y red ya se prepararon antes**; no una promesa de preparar todo en cinco minutos.

- [ ] Ambos PCs encendidos, cargadores, suspensión desactivada desde ajustes durante la demo.
- [ ] Sistema de Vanessa identificado; si no es Windows, elegir alternativa al exe.
- [ ] Misma red alcanzable; IP y ruta comprobadas en ambos sentidos.
- [ ] Docker Desktop abierto cuando corresponda, motor listo y Linux containers.
- [ ] Terminal manager, terminal worker y terminal k6 abiertas en sus carpetas.
- [ ] SHA local y remoto contrastados; no se modifica la rama durante la demo.
- [ ] Dos nodos Ready/Active y una task backend en cada uno **si se afirma distribución física**.
- [ ] Puertos libres para la receta elegida; identificar qué proceso atiende cada URL.
- [ ] Imágenes del SHA disponibles y compatibles en ambos PCs; login GHCR previo si se necesita.
- [ ] Datos, cuenta verificada y catálogo no vacío en **la base del entorno que medirá k6**.
- [ ] Launcher preparado en Windows y `.env.demo` conservado junto a su volumen.
- [ ] Health, frontend y flujos asignados abiertos.
- [ ] GitHub Actions, job de publicación y Packages abiertos en pestañas.
- [ ] Reporte coverage y capturas descargados antes de perder Internet; identificar su SHA.
- [ ] Carpeta de evidencias creada; no grabar tokens, secrets ni `.env`.
- [ ] No ejecutar limpiezas, reseteos ni migraciones improvisadas.

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, su terminal local, raíz del repo; comandos comunes Bash/PowerShell.**
```text
git status --short
git branch --show-current
git rev-parse HEAD
git ls-remote origin refs/heads/main
docker context show
docker version
docker info --format '{{.OSType}} {{.Architecture}} {{.Swarm.LocalNodeState}}'
docker compose version
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
```

🟢 Esperado: Linux, server accesible, Compose v2. `git status` puede mostrar esta guía nueva; eso no cambia el SHA auditado. 🔴 Sin server no seguir al deploy. Sin Internet, la consulta remota puede fallar: usar SHA local identificado y declarar la limitación.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash en raíz del repo; preparación de evidencia y lectura GitHub.**
```bash
mkdir -p evidencias-sustentacion
gh run list --repo TransformersAS/Transformers-AS --branch main --limit 8
gh run view 35717326168 --repo TransformersAS/Transformers-AS
docker stack ls
docker service ls
```

Si `gh` no está instalado o autenticado, usar las páginas enlazadas en §13. No instalar herramientas mientras corre el reloj técnico.

**Los cinco pasos obligatorios antes del minuto 00:** (1) resolver red/motores y comprobar dos nodos reales o declarar fallback; (2) fijar SHA, comprobar imágenes y evitar puertos ocupados; (3) tener sistema y datos listos con health y login; (4) comprobar carga corta, suite/reporte y acceso CI sin confundir versiones; (5) asignar terminales, réplica objetivo y evidencias, con plan B acordado.

# 3. Cómo verificar la red entre los dos computadores

## 3.1 macOS: no adivinar la interfaz

**DÓNDE EJECUTAR ESTO — 💻 CADA PC que use macOS, Terminal local, cualquier carpeta.**
```bash
networksetup -listallhardwareports
route -n get default
ifconfig
```

1. Buscar el puerto Wi-Fi/Ethernet y su `Device` real. No asumir que siempre sea `en0`.
2. Confirmar que la interfaz tiene `status: active` y dirección `inet` de la red del salón.
3. En este Mac se observó `en0` con `192.168.40.13`; repetir al cambiar de Wi-Fi.
4. Descartar `127.0.0.1`, `lo0`, `utun*` (VPN), bridges y direcciones internas de VM como dirección física elegida.
5. Una VPN puede cambiar la ruta aunque Wi-Fi conserve su IP. Confirmar ruta hacia el otro PC.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal, cualquier carpeta; solo si en0 fue la interfaz comprobada.**
```bash
ipconfig getifaddr en0
route -n get 192.168.40.13
```

El segundo comando hacia la IP propia no demuestra alcance desde Vanessa. Si la IP cambia, escribir la nueva en la hoja §26 y reemplazar `192.168.40.13` en **todos** los comandos y URLs de esta guía.

## 3.2 Windows

**DÓNDE EJECUTAR ESTO — 💻 PC Windows, PowerShell local, cualquier carpeta.**
```powershell
ipconfig
Get-NetIPConfiguration
Get-NetConnectionProfile
Get-NetFirewallProfile | Select-Object Name,Enabled
```

Elegir el adaptador Wi-Fi/Ethernet conectado, su IPv4 y gateway. No elegir `vEthernet`, WSL, VPN ni loopback. Una red marcada Pública puede tener reglas más restrictivas; revisar la política con quien administra la red, sin desactivar todo el firewall ni cambiar el perfil de una red no confiable.

## 3.3 Linux

**DÓNDE EJECUTAR ESTO — 💻 PC Linux, Bash local, cualquier carpeta.**
```bash
ip -br address
ip route
```

Elegir interfaz LAN activa y ruta al otro PC. Si el motor está en VM, la IP de esa VM debe ser alcanzable desde el otro motor: la IP del computador anfitrión no basta.

## 3.4 Probar desde PC 2, y luego repetir al revés

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER macOS/Linux, terminal local, cualquier carpeta; candidato manager todavía 192.168.40.13.**
```bash
ping -c 3 192.168.40.13
nc -vz -w 3 192.168.40.13 2377
nc -vz -w 3 192.168.40.13 7946
```

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER Windows, PowerShell local, cualquier carpeta.**
```powershell
ping -n 3 192.168.40.13
Test-NetConnection 192.168.40.13 -Port 2377
Test-NetConnection 192.168.40.13 -Port 7946
```

🟢 Esperado: alcance IP y, **después de tener manager activo**, TCP 2377 alcanzable. Antes de init puede no haber listener y eso por sí solo no prueba firewall. Ping bloqueado no significa servicio bloqueado: contrastar TCP y navegador. TCP abierto **no demuestra UDP ni overlay**.

| Síntoma | Interpretación / siguiente paso |
|---|---|
| Ningún PC alcanza al otro, ambos navegan Internet | Posible aislamiento de clientes del Wi-Fi; misma SSID no garantiza LAN entre equipos |
| IP encontrada difiere | Usar la IP nueva, no insistir con la candidata |
| Ping funciona, 2377 falla tras init | Revisar listener del motor, firewall y NAT de VM |
| Join funciona, tasks no se comunican | Revisar 7946 TCP/UDP y 4789 UDP, overlay, NAT |
| Solo funciona dentro de WSL/VM | Se está midiendo otra red/contexto; no equivale a acceso LAN |
| Route apunta VPN | Resolver ruta con administrador antes de desplegar |

Puertos entre motores: **2377/TCP** control del manager; **7946/TCP y UDP** descubrimiento; **4789/UDP** datos overlay. Permitir además los puertos publicados de la receta (18000/18090) hacia clientes. No abrir estos puertos de control a Internet. La receta requiere alcance bidireccional; un reenvío TCP aislado no resuelve VXLAN UDP. Referencia primaria: [red Swarm de Docker](https://docs.docker.com/engine/swarm/networking/).

**Docker Desktop es el mayor bloqueo probable:** el daemon Linux está en una VM. Que el Mac tenga 192.168.40.13 no significa que esa IP sea enrutable hacia todos los puertos del daemon. El estado observado anuncia 192.168.65.3. **NO VERIFICADO: cluster físico de dos PCs.** Si no hay red de motores alcanzable, usar motores Linux/VMs con red apropiada previamente preparados por el equipo; no improvisar la reconstrucción del cluster ni dejar el actual durante la demo.

📸 Capturar IP y prueba de conexión sin datos privados. Pasar a §8 solo cuando ambas personas sepan qué IP pertenece a cada motor y cada computador.

# 4. Arranque del Marketplace con Marketplace.exe

**DÓNDE HACER ESTO — 💻 PC Windows de Vanessa, Explorador de archivos; carpeta raíz de la entrega. No ejecutar el exe en macOS/Linux.**

1. Descomprimir la entrega completa en carpeta con permisos de escritura, fuera del ZIP.
2. Verificar `Marketplace.exe`, `Cerrar Marketplace.cmd`, `compose.yaml`, `compose.demo.yaml`, `frontend/` y `backend/demo/`, con Dockerfiles, `.dockerignore`, wrapper y `.mvn/` incluidos. El artifact del workflow contiene **solo el exe**, no toda la entrega.
3. Abrir Docker Desktop, esperar Engine listo, usar Linux containers.
4. Comprobar que 4300, 8080 y 3307 no estén ocupados por otra instalación.
5. Hacer doble clic una sola vez. Mantener visible la ventana.
6. Debe mostrar “Comprobando Docker...”, “Preparando entorno de demostración...”, “Iniciando base de datos...”, backend y frontend.
7. Esperar. La primera build requiere Internet y puede tardar varios minutos; no hay medición actual de duración. Cada llamada Docker tiene límite de 45 minutos; esperas Compose: MySQL 300 s, backend 300 s, frontend 180 s; comprobación HTTP final hasta 3 min. Esos límites **no son un tiempo garantizado de arranque**.
8. Debe aparecer “Marketplace está listo.” y abrir `http://localhost:4300`.
9. Si el navegador no abre automáticamente, abrir esa URL a mano en **ese Windows**.
10. 📸 Capturar launcher listo y GUI, sin contraseñas. Continuar al flujo solo con readiness UP.

**Secuencia comprobada por lectura de `Program.cs`:** verifica Docker CLI, motor Linux y Compose; busca la raíz desde la ubicación del exe y sus ancestros; genera `.env.demo` si falta; usa proyecto fijo `marketplace-demo`; inicia MySQL; reconstruye/recrea backend y frontend; verifica `/healthz` y `/api/actuator/health/readiness` vía 4300; abre navegador. El perfil demo se activa en `compose.demo.yaml`, usa fixture Flyway y `DemoProvisioning`. No llama `deploy.sh` ni forma Swarm.

Genera dos contraseñas aleatorias de 256 bits. Fuerza base/usuario `marketplace_demo` y puertos 4300/8080/3307 aunque `.env.demo` tenga otros valores; conserva claves existentes. Si no existe `.env`, lo copia desde `.env.demo`. El volumen es `marketplace-demo_mysql_data`. **Conservar `.env.demo` junto a ese volumen.**

El backend demo prepara datos reales y restaura el fixture en cada nuevo inicio; pagos/reembolsos y proveedores indicados por el perfil son simulados. No aplicar ese perfil a producción. No volver a abrir el launcher durante una demostración funcional.

Cerrar la ventana **no detiene** contenedores. `Cerrar Marketplace.cmd` invoca el exe con `--stop`, que usa Compose stop y conserva datos. Cerrar primero la ventana previa: el bloqueo del launcher se mantiene mientras la ventana Windows sigue abierta y puede impedir la segunda instancia.

⚠️ HISTÓRICO: documentación reporta validaciones en macOS y E2E, pero deja pendiente doble clic nativo Windows. OBSERVADO: workflow de construcción Windows exitoso (§15). **Eso no acredita doble clic, SmartScreen ni navegador en el PC de Vanessa.**

# 5. Si Marketplace.exe no abre

**Árbol rápido:** ¿Windows bloquea antes de abrir? A. ¿No hay ventana? B/I. ¿Ventana con error Docker? C/D. ¿Falla al levantar? E/F/G. ¿Dice listo pero no se ve? H. ¿Credenciales/entorno? J. ¿Descargas? K.

| Caso | Receta y criterio para continuar |
|---|---|
| A — SmartScreen | Verificar que es la entrega del equipo y su procedencia. En Windows, si la política permite: Más información → Ejecutar de todas formas. Si la institución lo bloquea, no desactivar protección: usar el entorno ya preparado y mostrar artifact. No afirmar ejecutable firmado. |
| B — Doble clic sin efecto | Confirmar extracción del ZIP, Windows x64 compatible y que no haya otra ventana preparando el sistema. Abrir PowerShell en la carpeta y ejecutar el exe como abajo. El programa mantiene abierta la ventana incluso después de éxito/error: no es por sí solo un cuelgue. |
| C — Docker apagado | Abrir Docker Desktop, esperar Engine listo, comprobar docker info, cerrar ventana anterior y reintentar. |
| D — Windows containers | Cambiar a Linux containers en Docker Desktop, esperar motor y comprobar OSType=linux. No seguir si el motor es Windows. |
| E — Puerto ocupado | §22. Identificar servicio propietario. No matar procesos al azar. Los puertos del launcher están forzados por código: editar .env.demo no los cambia. |
| F — Compose falla | Leer log y ps/logs con ambos YAML y proyecto correcto. Si falta Compose v2, preparar herramienta antes; no sustituir por otro proyecto accidentalmente. |
| G — Backend unhealthy | §20: MySQL, secretos consistentes, migraciones, fixture, recursos. Wait-timeout no implica que deba borrarse el volumen. |
| H — Frontend no abre | §19: abrir localhost:4300 en el mismo Windows; revisar frontend health y readiness proxy por separado. |
| I — Archivos faltantes | Restaurar entrega completa. FindRoot exige ambos YAML, frontend/ y backend/demo/. Artifact exe aislado no basta. Si falla antes de abrir log, no habrá marketplace.log nuevo. |
| J — .env.demo inconsistente | No borrarlo. Restaurar copia original que corresponde al volumen. Cambiar contraseña del archivo no cambia usuarios de MySQL ya inicializado. Revisar sin proyectar secretos. |
| K — Primera build sin Internet | No hay solución garantizada: launcher siempre pide build. Usar sistema previamente construido/arrancado y declarar que no se está probando primer arranque offline. |

**DÓNDE EJECUTAR ESTO — 💻 PC Windows del launcher, PowerShell abierto desde su carpeta: Explorador → barra de dirección → escribir powershell → Enter.**
```powershell
Get-Location
Test-Path .\Marketplace.exe
Test-Path .\compose.yaml
Test-Path .\compose.demo.yaml
Test-Path .\frontend
Test-Path .\backend\demo
docker info --format '{{.OSType}}'
.\Marketplace.exe
```

**DÓNDE EJECUTAR ESTO — 💻 MISMO Windows, SEGUNDA ventana PowerShell, raíz de la entrega; diagnóstico del launcher.**
```powershell
Get-Content .\launcher-logs\marketplace.log -Tail 100
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml ps
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml logs --tail 100 backend frontend mysql
curl.exe --fail --max-time 5 http://localhost:4300/healthz
curl.exe --fail --max-time 5 http://localhost:4300/api/actuator/health/readiness
```

El log real es **`launcher-logs/marketplace.log` relativo a la raíz encontrada**, se sobrescribe al iniciar una nueva ejecución. Guardarlo antes de reintentar. El código oculta las dos contraseñas conocidas; revisar otros datos antes de proyectarlo. Comandos Compose manuales pueden heredar variables de la terminal distintas a las forzadas por el exe: usar una terminal limpia y la configuración original, no mostrar `compose config` con secretos.

# 6. Demo funcional general

**DÓNDE HACER ESTO — 💻 PC con navegador que accede al entorno funcional preparado; no terminal Docker.**

1. Confirmar la URL elegida: Windows launcher `http://localhost:4300`; Swarm preparado en esta guía `http://192.168.40.13:18000` si esa sigue siendo la IP verificada.
2. Mostrar frontend, entrar con la cuenta prevista y ejecutar los flujos asignados.
3. Comprobar refresco/persistencia cuando corresponda; distinguir el proveedor simulado de la BD real.
4. No tocar Docker ni relanzar el exe mientras se opera la GUI.
5. 📸 Capturar resultado y refresco; pasar a cobertura al minuto 05.

**Para el paso exacto de cada CU, utilizar las guías individuales.** La ruta antigua `docs/sustentacion-cu08-11.md` no está versionada en main; usar [cu-08-autenticacion-cu-11-cancelacion.md](cu-08-autenticacion-cu-11-cancelacion.md). No se reproducen aquí instrucciones de CUs particulares.

# 7. Pruebas de integración y coverage

## Lo que se puede decir con rigor

| Tema | Estado |
|---|---|
| Requisito oficial | 100 % cobertura de integración |
| Gate normal CI | `LINE / COVEREDRATIO >= 0.95`, suite normal, mezcla tipos de tests |
| Gate perfil `integration-coverage` | `LINE / MISSEDCOUNT maximum=0`, integración exclusiva |
| Selección actual | 81 clases auditadas por `integration-suite.py`, allowlist `src/test/integration-tests.includes` |
| Reporte histórico en docs | 80 clases, 864 tests, 0 fallos/errores/omitidos; 6811 líneas cubiertas, 195 sin cubrir, total 7006: **97,216671 %** |
| Resultado histórico del gate | Falló verify por 195 líneas missed; no 100 % |
| Porcentaje del SHA actual | **NO VERIFICADO**: no suite nueva ni XML local en esta auditoría |

La documentación histórica no incluye todavía la clase adicional del manifiesto actual. No presentar 97,216671 % como una medición recién hecha de este SHA. Tampoco presentar un CI verde de 95 % como prueba del requisito del 100 %.

Las pruebas usan Spring, Testcontainers/MySQL; algunas integraciones de proveedores usan WireMock o mecanismos de fallos controlados. Eso no equivale a hablar con proveedores externos reales. La cobertura mide líneas ejecutadas, no “porcentaje de requisitos cumplidos”.

## Reproducir ANTES, no competir con k6

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal/Bash, raíz del repositorio; requiere JDK 21, Python 3 y Docker listo.**
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
docker info --format '{{.OSType}}'
python3 backend/demo/scripts/integration-suite.py
bash backend/demo/scripts/run-integration-coverage.sh
```

**DÓNDE EJECUTAR ESTO — 💻 PC Windows de pruebas, PowerShell, raíz; JDK 21 en PATH y Python instalado como py.**
```powershell
java -version
docker info --format '{{.OSType}}'
py backend/demo/scripts/integration-suite.py
Set-Location backend/demo
.\mvnw.cmd -Pintegration-coverage clean verify
```

🟢 Esperado: ejecución de integración, MySQL Testcontainers, XML/HTML y gate evaluado. **No prometer BUILD SUCCESS**: cualquier línea missed debe fallar. Duración actual no medida; CI validate admite hasta 20 min, pero ese timeout tampoco estima la suite exclusiva. No correr clean sobre reportes que no se hayan guardado.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal, raíz; después de terminar la suite, aunque verify falle por gate.**
```bash
python3 backend/demo/scripts/integration-coverage-report.py
open backend/demo/target/site/jacoco/index.html
```

**DÓNDE EJECUTAR ESTO — 💻 PC Windows de pruebas, PowerShell, carpeta backend/demo después del bloque anterior.**
```powershell
py scripts/integration-coverage-report.py
Start-Process target/site/jacoco/index.html
```

Revisar **LINE**, covered/missed/total, 0 fallos/errores/omitidos y `Complete allowlisted suite: True`. El reporte HTML se reutiliza también por la suite normal: confirmar que fue producido con el perfil, `target/jacoco-integration.exec` y `target/surefire-integration-reports/`, no solo por la existencia del HTML. Guardar consola y SHA juntos.

🔴 Si no hay reporte fresco: mostrar [documentación histórica](../pruebas/cobertura-integracion-backend.md) y decir: “El requisito es 100 %. El último resultado documentado es 97,216671 %; el perfil conserva gate de cero líneas pendientes. El SHA actual tiene una clase adicional y no estamos atribuyéndole ese porcentaje sin nueva corrida”. No inventar un HTML ausente.

📸 Capturar resumen, contador LINE y estado gate, no solo una clase al 100 %. Pasar a performance al terminar los dos minutos reservados.

# 8. Docker Swarm en dos computadores — desde cero

**Esta receta NO fue ejecutada por la auditoría.** Tiene una barrera inicial: el Mac ya es manager de un cluster con datos. No inicializar otro ni abandonar el actual para seguir literalmente “desde cero”. Usar el cluster existente solo si su red permite incorporar el segundo motor; de lo contrario preparar otro entorno de forma planificada fuera de la exposición. **No hay una corrección automática de NAT/VM en este repositorio.**

## 8.1 Elegir manager

PC 1 Sofía es candidata, IP LAN `192.168.40.13`. Ejecutar §3 y comprobar que la IP del **motor** anunciada al worker es alcanzable. No usar dirección de VPN, loopback ni la VM privada inaccesible desde PC 2. Mantener una hoja con IP manager, IP worker, hostname e ID Docker.

## 8.2 Revisar estado actual en AMBOS computadores

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, terminal local, cualquier carpeta.**
```text
docker context show
docker info --format '{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}} {{.Swarm.NodeID}} {{.Swarm.NodeAddr}}'
```

- `inactive`: todavía no pertenece a cluster; apto para init/join tras red verificada.
- `active true`: manager. Solo él puede listar nodos y desplegar stack.
- `active false`: worker. No ejecutar deploy aquí.
- Estado error/pending/locked: detener receta, diagnosticar; no forzar limpieza.
- Worker ya unido al cluster correcto: no repetir join. En otro cluster: resolver preservación de cargas fuera de la demo.

⚠️ `docker swarm leave --force` en un manager puede destruir el control de ese cluster y dejar cargas/datos sin administración. **No se incluye como paso de esta receta.** En el Mac auditado, conservar `marketplace` y su volumen mientras se decide el entorno.

## 8.3 Inicializar manager, SOLO si está inactive y la red está validada

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal del motor Linux elegido, cualquier carpeta; únicamente cluster nuevo y candidato confirmado.**
```bash
docker swarm init --advertise-addr 192.168.40.13
```

🟢 Esperado: “Swarm initialized” y comando para agregar worker. Si Docker dice dirección no disponible o los puertos no llegan desde PC 2, **no repetir con IPs al azar**: §18. No ejecutar en el manager ya activo auditado. Referencia de sintaxis: [docker swarm init](https://docs.docker.com/reference/cli/docker/swarm/init/).

## 8.4 Unir PC 2

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER activo y alcanzable, terminal local, cualquier carpeta; no compartir pantalla del token.**
```bash
docker swarm join-token worker
```

Copiar el comando completo por canal privado a Vanessa. Si muestra 192.168.65.3, comprobar alcance real; sustituir solo el texto del comando no arregla la red interna del cluster.

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER, terminal del motor elegido, cualquier carpeta; plantilla que se reemplaza por el comando REAL emitido por el manager.**
```text
docker swarm join --token REEMPLAZAR_TOKEN_REAL 192.168.40.13:2377
```

🟢 Esperado: “This node joined a swarm as a worker”. No fotografiar token ni guardarlo en Git. Mantener ambas terminales abiertas; pasar a PC 1.

## 8.5 Verificar cluster

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, cualquier carpeta.**
```bash
docker node ls
docker node inspect --format '{{.ID}} {{.Description.Hostname}} {{.Status.Addr}} {{.Spec.Role}}' self
```

`Ready` = daemon reporta disponible; `Active` = permite scheduling; `Leader` = manager líder; columna manager vacía suele corresponder a worker, confirmar Role si hace falta. Deben aparecer **dos IDs de motores diferentes**. Ambos Docker Desktop pueden llamarse `docker-desktop`: no usar solo hostname para demostrar PCs; comparar el NodeID de cada terminal física.

📸 Capturar tabla y las dos pantallas con sus IDs. Si aparece un solo nodo, no continuar con la afirmación “dos computadores”.

## 8.6 Labels y placement

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, raíz; copiar ID del WORKER real de node ls en el prompt.**
```bash
MANAGER_NODE_ID=$(docker info --format '{{.Swarm.NodeID}}')
read -r -p 'ID real del WORKER: ' WORKER_NODE_ID
docker node inspect --format '{{.ID}} {{.Spec.Role}} {{.Status.State}}' "$WORKER_NODE_ID"
docker node update --label-add backend_zone=pc1 "$MANAGER_NODE_ID"
docker node update --label-add backend_zone=pc2 "$WORKER_NODE_ID"
docker node inspect --format '{{.ID}} {{json .Spec.Labels}}' "$MANAGER_NODE_ID" "$WORKER_NODE_ID"
```

🟢 Esperado: etiquetas distintas. `stack.yml` tiene `spread: node.labels.backend_zone` para backend y frontend; **es preferencia, no constraint ni max_replicas_per_node**. Revisar después task por task: no basta ver 2/2. MySQL exige manager y `node.id == MYSQL_NODE_ID`; volumen local, una sola réplica.

## 8.7 GHCR y versión

En cluster real, `deploy.sh` **hace pull de ambas imágenes**; usa `--with-registry-auth --resolve-image always`. No construye localmente. Los tags por defecto son latest; fijar `sha-<SHA completo>` de una publicación existente. En las consultas realizadas, los manifiestos backend (consulta inicial) y frontend (consulta al cierre) del SHA auditado respondieron **manifest unknown**, coherente con publicación aún en curso; no desplegar ese tag hasta comprobar publicación.

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, terminal local, cualquier carpeta; comprobación sin descargar imágenes.**
```text
docker version --format '{{.Server.Os}}/{{.Server.Arch}}'
docker manifest inspect ghcr.io/transformersas/transformers-as-backend:sha-770f3c6da98a1885b69bf43c606a60986b2468e6
docker manifest inspect ghcr.io/transformersas/transformers-as-frontend:sha-770f3c6da98a1885b69bf43c606a60986b2468e6
```

🟢 Esperado: manifiestos con linux/amd64 y linux/arm64 para los PCs que los necesiten. No confundir manifests de attestations `unknown/unknown` con plataforma ejecutable. `denied` pide revisar acceso; `manifest unknown` pide revisar tag/publicación. No sustituir silenciosamente por latest.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER y, para predescarga manual, PC WORKER; terminal local privada, cualquier carpeta.**
```text
docker login ghcr.io
```

Introducir usuario y credencial de lectura del paquete en el prompt, no en pantalla compartida ni línea de comandos. Permisos dependen del paquete/organización; no se verificó si son públicos. El manager transmite credenciales de registro a los agentes mediante deploy; el worker no necesita login interactivo adicional para ese mecanismo, pero sí para sus pulls manuales si son privados.

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, terminal local, cualquier carpeta; solo tras confirmar manifiestos del SHA.**
```text
docker pull ghcr.io/transformersas/transformers-as-backend:sha-770f3c6da98a1885b69bf43c606a60986b2468e6
docker pull ghcr.io/transformersas/transformers-as-frontend:sha-770f3c6da98a1885b69bf43c606a60986b2468e6
docker pull mysql:8.4.11
```

Estas descargas son **preparación sugerida, no realizada**. No correrlas en plena prueba de performance. Si la publicación de main no termina, usar una versión anterior solo con su SHA visible y reconocer que no acredita el SHA actual.

## 8.8 Secrets

Para `STACK_NAME=transformers`, defaults reales:

| Secret externo | Entrada / generación |
|---|---|
| `transformers_db_password_v1` | Prompt oculto o `DB_PASSWORD_SECRET_FILE` |
| `transformers_mysql_root_password_v1` | Prompt oculto o `MYSQL_ROOT_PASSWORD_SECRET_FILE` |
| `transformers_logistics_webhook_secret_v1` | `LOGISTICS_WEBHOOK_SECRET_FILE` o valor aleatorio de 48 hex generado por script |

Se crean en manager y se montan según stack. Si existen se **reutilizan**, no se rota contenido. `.env` no se carga por `deploy.sh`. Las contraseñas DB deben corresponder al volumen si ya está inicializado.

**Preparación más rápida para un entorno nuevo:** ejecutar deploy interactivamente y proporcionar las dos claves en prompts ocultos fuera de pantalla compartida; el webhook se genera automáticamente. Guardar las claves en gestor seguro. Después, la repetición en vivo reutiliza los secrets sin preguntar. No borrar secrets para volver a ver prompts.

Para CD no interactivo: preparar archivos fuera del repo, de una sola línea **sin salto final**, no vacíos, permisos 0600 cuando aplique; exportar las tres variables `*_SECRET_FILE` con rutas reales. El YAML CD lee esas rutas de variables GitHub, **no transporta archivos desde GitHub**: deben existir en el runner. No proyectar valores.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, cualquier carpeta; solo metadatos.**
```bash
docker secret ls
```

No hay necesidad de imprimir secretos para probar que existen. Si el servicio usa otros nombres, inspeccionar referencias del servicio sin mostrar contenido.

## 8.9 Un solo script

Antes: red apta, manager correcto, dos nodos, tags existentes, puertos 18000 y 18090 libres, recursos suficientes y secrets/credenciales preparados. El stack nuevo `transformers` coexistiría con `marketplace`; no desplegar ambos si no hay memoria suficiente. Si esos prerrequisitos no se cumplen, **parar y usar §18**, sin cambiar infraestructura a ciegas.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash (Terminal Mac/Linux o Git Bash Windows), raíz del repo; configuración previa explícita.**
```bash
export STACK_NAME=transformers
export BACKEND_IMAGE_TAG=sha-770f3c6da98a1885b69bf43c606a60986b2468e6
export FRONTEND_IMAGE_TAG="$BACKEND_IMAGE_TAG"
export FRONTEND_HOST_PORT=18000
export BACKEND_HOST_PORT=18090
```

No reutilizar inadvertidamente `MYSQL_NODE_ID`, `DB_NAME`, `DB_USER`, `*_SECRET` o `SWARM_*_URL` de otro entorno. Revisar sus nombres/valores no secretos en privado. Si no están definidos, deploy usa defaults documentados y conserva el nodo MySQL de un servicio existente del mismo stack.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, MISMA terminal Bash y raíz, después de aprobar todos los prerrequisitos. Este es EL script de despliegue.**
```bash
bash deploy.sh
```

🟢 Esperado: descarga/verifica imágenes, valida healthchecks, elige manager MySQL, crea/reutiliza secrets, despliega frontend/backend/mysql, espera réplicas 2/2, 2/2, 1/1 y tres ciclos consecutivos de health UP separados por 5 s. Timeout de convergencia default 600 s, **después** de pulls/preparación. No prometer que tarda tres minutos en frío.

El mensaje final es “Frontend y API disponibles; réplicas convergidas y readiness accesible...”. Si termina con código distinto de cero, conservar salida; deja stack para diagnóstico. No afirmar éxito solo porque `docker stack deploy` aceptó YAML.

**En vivo:** mostrar una repetición del mismo script sobre el entorno preparado, sin cambiar tags, secretos ni puertos; explicar que la creación del cluster y configuración se hicieron antes. Si tarda más del bloque, mostrar estado y declarar operación en curso, no esperar consumiendo todos los 20 minutos.

## 8.10 Verificar distribución física

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz, después del despliegue propuesto.**
```bash
docker node ls
docker service ls
docker stack services transformers
docker service ps --no-trunc transformers_backend
docker service ps --no-trunc transformers_frontend
docker service ps --no-trunc transformers_mysql
docker stack ps --no-trunc transformers
```

Interpretación: NAME indica servicio y slot; NODE indica motor que ejecuta; DESIRED STATE es lo solicitado; CURRENT STATE dice si la task está Running/Failed/Pending; ERROR explica rechazo. Filas con `\_` son historial, no más réplicas actuales. `2/2` significa cantidad convergida, **no dos computadores**.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, raíz; vincular tareas activas con NodeID aunque los hostnames sean iguales.**
```bash
docker service ps --filter desired-state=running -q transformers_backend | while read -r task; do
  docker inspect --type task --format '{{.ID}} node={{.NodeID}} state={{.Status.State}}' "$task"
done
```

En las terminales de ambos PCs comparar con su `.Swarm.NodeID` de §8.2. 🟢 Esperado: un backend en PC 1 y otro en PC 2. Si no, preferencia spread no logró separación; **no certificar distribución**. No hacer un force update improvisado durante functional/performance.

# 9. Prueba de deployability

💻 Sofía comparte terminal manager, Vanessa deja visible su NodeID en la otra pantalla.

1. Mostrar dos PCs y §8.5: dos motores Ready/Active.
2. Mostrar §8.10: backend y frontend, placement real, MySQL manager.
3. Mostrar el comando único de §8.9 y la salida convergida; los preparativos son configuración previa.
4. Abrir frontend del Swarm (18000) y readiness proxy. Un UP de 4300 no demuestra ese stack.
5. Mostrar persistencia con el flujo ya asignado, sin inventar otra prueba funcional.
6. 📸 Guardar script/salida, nodos, tasks, health y GUI con SHA visible.

Guion ≤20 s **solo si se observó todo**: “Este manager administra dos motores en dos computadores. Hay dos réplicas backend y dos frontend; aquí se ve dónde están. El mismo deploy.sh despliega los tres servicios y verifica convergencia y readiness. MySQL tiene una réplica y no ofrece alta disponibilidad”.

Si hay un nodo: “Este ensayo acredita despliegue por script y réplicas locales; **no cumple la distribución física en dos computadores**”.

# 10. Performance

## Qué scripts existen realmente

| Script versionado | Carga y endpoints | Thresholds por defecto |
|---|---|---|
| `scripts/k6/catalog-browse.js` | Setup registro vendedor, CSRF y login; bucle GET `/api/products` y `/api/products/{id}` | `catalog_list_duration` p95 ≤3000 ms; `catalog_detail_duration` p95 ≤3000 ms; `catalog_error_rate` <0,02 |
| `scripts/k6/seller-register.js` | CSRF + POST `/api/sellers/register`, cuentas únicas | `seller_register_duration` p95 ≤4000 ms; `seller_register_error_rate` <0,02 |

Ambos: `constant-vus`, VUS=50 y DURATION=1m por defecto, gracefulStop=10s. La corrida catálogo con **100 VUs** es la que la documentación asocia al ASR catálogo. El documento original `Decisiones_Arquitectonicas_Marketplace.md` citado por el script **no está en main**: no certificar todos sus requisitos a partir de una cita indirecta.

No hay script de availability dedicado: §11 reutiliza catálogo con duración 3m. El registro mide BCrypt/escritura y crea cuentas; no sustituye la prueba de catálogo.

**Prerrequisito crítico:** catálogo no vacío y credenciales válidas en el entorno objetivo. `setup()` intenta registrar cuenta, pero no confirma correo; el registro actual envía verificación y el script no la completa. Evitar depender de esa cuenta nueva: usar una cuenta de prueba **ya verificada** mediante `CATALOG_EMAIL`/`CATALOG_PASSWORD`. Su setup puede devolver conflicto por cuenta existente y continuar; no confundir ese HTTP de preparación con el error rate del catálogo. Todas las VUs usan esa misma identidad con cookie jars separados: 100 VUs no significa 100 compradores diferentes.

## Preparación de datos: fuera de los 20 minutos

Swarm no activa perfil demo; exportar `SPRING_PROFILES_ACTIVE=demo` en la terminal no lo añade al stack actual. No esperar allí las cuentas del exe. Se encontraron seeds `cu19-demo-seed.sh`, `cu20-demo-seed.sh`, `cu23-demo-seed.sh`, `cu24-25-demo-seed.sh`, `cu24-25-demo-events.sh`, `cu08-real-seed.sql` y el generador performance `.sh`/`.py` bajo `scripts/`. No correrlos indiscriminadamente sobre el MySQL observado.

El generador performance tiene defaults 100 usuarios del pool, 1.000 productos y 10.000 pedidos sintéticos, **107 cuentas totales** contando auxiliares. Requiere Docker, Python 3 y htpasswd o imagen httpd:2.4; DB ya creada/migrada, sin tráfico y desechable. No acredita pagos reales. Ver [datos y rendimiento](../pruebas/datos-demostracion-y-rendimiento.md).

**DÓNDE EJECUTAR ESTO — 💻 PC que aloja el MySQL DE PRUEBAS DESECHABLE, Bash, raíz; solo preparación previa, con base aislada y backend sin tráfico. NO ejecutar sobre el stack auditado por conveniencia.**
```bash
read -r -p 'Nombre/ID real del contenedor MySQL desechable: ' MYSQL_CONTAINER
read -r -p 'Base desechable ya migrada: ' DB_NAME
read -r -p 'Usuario MySQL de esa base: ' DB_USER
read -r -s -p 'Contraseña MySQL de esa base: ' DB_PASSWORD
printf '\n'
read -r -s -p 'Contraseña para cuentas sintéticas: ' DEMO_PASSWORD
printf '\n'
export MYSQL_CONTAINER DB_NAME DB_USER DB_PASSWORD DEMO_PASSWORD
bash scripts/performance-demo-seed.sh
unset DB_PASSWORD DEMO_PASSWORD
```

🟢 Esperado: resumen de inserción/reutilización de datos. 🔴 Si aborta, no desactivar constraints ni activar reset. Este paso no configura por sí solo el backend para esa base: **debe ser ya la base del entorno de performance acordado**. Si no existe un entorno seguro preparado, usar catálogo/cuenta ya preparados en el entorno disponible y declarar su tamaño real; no afirmar escala 1.000/10.000 sin contarla. No se ejecutó este seed en la auditoría.

## 10.1 Preparar cliente y prueba 50 usuarios

Ruta de carga principal: **frontend del Swarm propuesto** `http://192.168.40.13:18000`, sin agregar `/api` a BASE_URL porque el script ya lo añade. Si se usa Compose, BASE_URL es `http://127.0.0.1:4300` desde ese host y el resultado se etiqueta Compose. No mezclar resultados.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER/cliente de carga, terminal Bash dedicada, raíz; introducción privada de cuenta y configuración de prueba.**
```bash
mkdir -p evidencias-sustentacion
export BASE_URL=http://192.168.40.13:18000
read -r -p 'Correo de cuenta de prueba ya verificada: ' CATALOG_EMAIL
read -r -s -p 'Contraseña de esa cuenta: ' CATALOG_PASSWORD
printf '\n'
export CATALOG_EMAIL CATALOG_PASSWORD
k6 version
k6 run -e VUS=1 -e DURATION=10s scripts/k6/catalog-browse.js
```

Esta corrida corta es preparación, no acredita ASR. Confirmar que hay muestras **tanto de listado como de detalle** y no aparecen errores de catálogo vacío/login. No ejecutar la prueba en una pantalla que muestre contraseñas.

**DÓNDE EJECUTAR ESTO — 💻 MISMO PC y MISMA terminal Bash de k6, raíz, con BASE_URL y cuenta ya exportadas.**
```bash
k6 run -e VUS=50 -e DURATION=1m --summary-export evidencias-sustentacion/catalogo-50.json scripts/k6/catalog-browse.js
printf 'Exit code k6: %s\n' "$?"
```

🟢 Esperado: duración nominal 60 s más setup/login/graceful stop, no tiempo fijo exacto. Capturar salida completa antes de limpiar consola. Ver métricas §10.3, escribirlas en §26; luego 100 VUs.

## 10.2 Prueba 100 usuarios

**DÓNDE EJECUTAR ESTO — 💻 MISMO PC/terminal Bash de carga, raíz, mismas URL/cuenta/dataset para comparación.**
```bash
k6 run -e VUS=100 -e DURATION=1m --summary-export evidencias-sustentacion/catalogo-100.json scripts/k6/catalog-browse.js
printf 'Exit code k6: %s\n' "$?"
```

No cambiar `P95_LIMIT_MS` ni `ERROR_RATE_LIMIT`. Guardar k6 version, SHA, entorno, PCs, tamaños de datos y carga concurrente adicional. El ASR se evalúa con salida real, no con una estimación.

## Alternativa Docker si k6 no está instalado

Predescargar la imagen k6 antes. Estos bloques son alternativas a los anteriores, **no una tercera prueba simultánea**. El tag no fijado de `grafana/k6` requiere registrar la versión/digest que se utilice; no se inventó una versión del repo.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER macOS/Linux, Bash, raíz; variables de cuenta del bloque anterior exportadas y LAN manager verificada.**
```bash
docker run --rm -v "$PWD/scripts/k6:/scripts:ro" -v "$PWD/evidencias-sustentacion:/results" -e BASE_URL -e CATALOG_EMAIL -e CATALOG_PASSWORD -e VUS=50 -e DURATION=1m grafana/k6 run --summary-export /results/catalogo-50.json /scripts/catalog-browse.js
docker run --rm -v "$PWD/scripts/k6:/scripts:ro" -v "$PWD/evidencias-sustentacion:/results" -e BASE_URL -e CATALOG_EMAIL -e CATALOG_PASSWORD -e VUS=100 -e DURATION=1m grafana/k6 run --summary-export /results/catalogo-100.json /scripts/catalog-browse.js
```

**DÓNDE EJECUTAR ESTO — 💻 PC Windows cliente, PowerShell, raíz; alternativa Docker completa.**
```powershell
New-Item -ItemType Directory -Force evidencias-sustentacion | Out-Null
$env:BASE_URL = 'http://192.168.40.13:18000'
$env:CATALOG_EMAIL = Read-Host 'Correo de prueba ya verificado'
$claveCarga = Read-Host 'Contraseña de prueba' -AsSecureString
$env:CATALOG_PASSWORD = [System.Net.NetworkCredential]::new('', $claveCarga).Password
docker run --rm -v "${PWD}/scripts/k6:/scripts:ro" -v "${PWD}/evidencias-sustentacion:/results" -e BASE_URL -e CATALOG_EMAIL -e CATALOG_PASSWORD -e VUS=50 -e DURATION=1m grafana/k6 run --summary-export /results/catalogo-50.json /scripts/catalog-browse.js
$LASTEXITCODE
docker run --rm -v "${PWD}/scripts/k6:/scripts:ro" -v "${PWD}/evidencias-sustentacion:/results" -e BASE_URL -e CATALOG_EMAIL -e CATALOG_PASSWORD -e VUS=100 -e DURATION=1m grafana/k6 run --summary-export /results/catalogo-100.json /scripts/catalog-browse.js
$LASTEXITCODE
```

Desde Docker, `127.0.0.1` es **el contenedor k6**, no el host. En Docker Desktop, para un servicio publicado en el mismo host se puede usar `http://host.docker.internal:18000` (o 4300 para Compose) después de comprobar alcance. Para un manager remoto usar su IP LAN. En Linux no asumir que `host.docker.internal` está definido; usar IP alcanzable del motor/host. Si el servicio Compose solo escucha loopback, no pretender acceder desde otro PC por su IP LAN.

## 10.3 Qué mirar

- `catalog_list_duration` y `catalog_detail_duration`: columna `p(95)`, cada una ≤3 s. Mirar ambas.
- `catalog_error_rate`: proporción de muestras marcadas error, <2 %. Incluye fallos login y catálogo vacío; no equivale exactamente a fallos de transporte HTTP.
- `catalog_throughput`: contador de detalles exitosos y su tasa por segundo; no contar esto como todos los requests HTTP.
- `http_reqs`: total HTTP y tasa req/s, incluye preparación/autenticación.
- `http_req_failed`: tasa de requests que k6 considera fallidos, distinta del rate de negocio.
- `checks`: verificaciones 200 de listado/detalle, distintas de cantidad de operaciones de usuario.

🟢 PASS solo con thresholds satisfechos y datos suficientes; 🔴 FAIL si se cruza un threshold o hay error de ejecución. Exit distinto de cero también puede ser un fallo de setup, no necesariamente rendimiento malo. Un 0 con detalle sin muestras exige investigar antes de acreditar catálogo completo.

`--summary-export` guarda JSON; abrirlo en editor si varía el esquema entre versiones. El resumen terminal mantiene nombres reales de métricas. Referencias: [salida de k6](https://grafana.com/docs/k6/latest/get-started/results-output/) y [opciones de exportación](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/).

## 10.4 Si performance falla

1. Guardar consola, JSON y exit code. No repetir diez veces hasta elegir la única verde.
2. Verificar BASE_URL/puerto/stack/SHA; no estar midiendo Compose antiguo por error.
3. Confirmar cuenta verificada, sesiones y catálogo no vacío. En este código, login no está en las Trends personalizadas, aunque sí afecta recursos y métricas HTTP globales.
4. Revisar CPU/memoria, builds/pruebas paralelas, MySQL, red y warmup.
5. Si existe evidencia previa cuantitativa, mostrarla **con su versión y entorno**. No se encontraron JSON k6 versionados; la documentación solo menciona un ensayo pequeño de 46 ms y ~60 ops/s que no acredita 50/100 VUs.
6. Decir “esta corrida incumplió [métrica], registramos [valor]”; pasar al siguiente bloque sin modificar thresholds.

# 11. Availability — receta literal

**Prerrequisitos:** dos backends Running/healthy en **dos nodos físicos**, frontend del Swarm accesible, datos/cuenta válidos, sin otras pruebas concurrentes. Esta prueba mata un proceso backend, **no el computador ni MySQL**. No prueba una SLA mensual ni alta disponibilidad total.

## 11.1 Verificar 2/2

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal de control, raíz.**
```bash
docker stack services transformers
docker service ps --filter desired-state=running --no-trunc transformers_backend
docker node ls
```

🟢 Esperado 2/2 y distribución §8.10. 🔴 Si hay solo un nodo, se puede demostrar reemplazo local etiquetado como parcial, no el requisito distribuido.

## 11.2 Identificar réplica del worker

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, terminal de control, raíz; seleccionar task activa del worker, NO del mysql.**
```bash
docker service ps --filter desired-state=running --no-trunc transformers_backend
read -r -p 'Task ID del backend situado en WORKER: ' TARGET_TASK_ID
docker inspect --type task --format 'Task={{.ID}} Node={{.NodeID}} Container={{.Status.ContainerStatus.ContainerID}} State={{.Status.State}}' "$TARGET_TASK_ID"
```

Copiar TaskID/ContainerID y verificar NodeID con Vanessa. Una task ID **no es** un container ID. Asegurar que se mata solo una y que queda otra operando.

## 11.3 Iniciar tráfico continuo

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER/cliente, terminal Bash de k6, raíz, cuenta y BASE_URL exportadas como §10; NO cerrar esta terminal.**
```bash
k6 run -e VUS=50 -e DURATION=3m --summary-export evidencias-sustentacion/availability-k6.json scripts/k6/catalog-browse.js
```

**DÓNDE EJECUTAR ESTO — 💻 PC cliente Windows, PowerShell de §10, raíz; alternativa si se usa Docker k6.**
```powershell
docker run --rm -v "${PWD}/scripts/k6:/scripts:ro" -v "${PWD}/evidencias-sustentacion:/results" -e BASE_URL -e CATALOG_EMAIL -e CATALOG_PASSWORD -e VUS=50 -e DURATION=3m grafana/k6 run --summary-export /results/availability-k6.json /scripts/catalog-browse.js
```

Esperar unos 30 s de tráfico estable tras autenticación. Sofía anuncia “tráfico iniciado”; Vanessa no mata nada antes. Esta corrida conserva los thresholds de catálogo, no inventa thresholds de recovery.

## 11.4 Matar UNA réplica, EN EL NODO DONDE ESTÁ

⚠️ Este comando interrumpe intencionalmente un contenedor backend de la demo. Solo ejecutarlo cuando el tráfico está activo y la otra réplica se confirmó. Nunca hacerlo sobre MySQL ni sobre un container seleccionado solo por posición de lista.

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER, Bash, cualquier carpeta; task y contenedor deben pertenecer a ESTE motor.**
```bash
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
read -r -p 'Container ID de ESA task backend, confirmado con manager: ' TARGET_CONTAINER_ID
docker inspect --format 'Service={{index .Config.Labels "com.docker.swarm.service.name"}} Task={{index .Config.Labels "com.docker.swarm.task.id"}} Health={{.State.Health.Status}}' "$TARGET_CONTAINER_ID"
```

Comparar Service=transformers_backend y TaskID del §11.2; **si difiere, detenerse**. Sofía prepara cronómetro común en pantalla para evitar comparar relojes de PCs no sincronizados.

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER, MISMA terminal Bash, solo después de la verificación y anuncio del manager.**
```bash
date -u '+T0 kill %Y-%m-%dT%H:%M:%SZ'
docker kill "$TARGET_CONTAINER_ID"
```

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER Windows, PowerShell, cualquier carpeta; alternativa completa a los dos bloques Bash.**
```powershell
docker ps --filter label=com.docker.swarm.service.name=transformers_backend --format '{{.ID}} {{.Names}} {{.Status}}'
$targetContainer = Read-Host 'Container ID del backend confirmado con manager'
docker inspect --format 'Service={{index .Config.Labels "com.docker.swarm.service.name"}} Task={{index .Config.Labels "com.docker.swarm.task.id"}} Health={{.State.Health.Status}}' $targetContainer
```

Revisar visualmente servicio/task antes del siguiente bloque.

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER Windows, MISMA PowerShell; solo tras verificación.**
```powershell
(Get-Date).ToUniversalTime().ToString('o')
docker kill $targetContainer
```

## 11.5 Observar reemplazo y medir recuperación

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal Bash de control, raíz; iniciar antes de kill y terminar con Ctrl+C después de estabilizar.**
```bash
while true; do
  date -u '+%Y-%m-%dT%H:%M:%SZ'
  docker service ls --filter name=transformers_backend
  docker service ps --no-trunc transformers_backend
  curl --fail --silent --show-error --max-time 3 http://192.168.40.13:18000/api/actuator/health/readiness
  sleep 2
done
```

🟢 Esperado: task antigua Failed/Shutdown, tal vez 1/2 visible, **task ID nuevo**, vuelve a 2/2. Puede no capturarse el 1/2 por la frecuencia de muestreo; no inventar captura intermedia. Restart policy tiene delay=10s más tiempo de arranque; no hay recovery garantizado de 10 s.

En el worker repetir listado e inspect health del contenedor nuevo. `Running` y `2/2` no bastan para afirmar healthy. Definir **T1** cuando hay task nueva, dos contenedores healthy (cada uno visto en su nodo) y readiness proxy vuelve a UP de forma estable. Anotar recovery=T1−T0 con cronómetro de Sofía; resolución del sondeo ≈2 s más tiempo de comandos. Separar **tiempo hasta reemplazo** de **interrupción de peticiones**: pueden no ser iguales.

📸 Capturar antes, task fallida, task nueva, 2/2 y resumen k6. Dejar terminar k6; no usar Ctrl+C en esa terminal salvo emergencia porque sería una corrida parcial.

## 11.6 Resultados cuantitativos

Copiar del resumen/JSON: `http_reqs` total y req/s; `http_req_failed` y su conteo/tasa; `checks` pasados/fallidos; ambas Trends de catálogo p95; `catalog_error_rate`; `catalog_throughput` count/rate; recovery manual.

Requests HTTP exitosos = total HTTP menos fallidos **según la clasificación de k6**. Si el JSON tiene contadores `passes`/`fails` de una Rate, comprobar su semántica: para `http_req_failed`, las muestras true representan fallo, no éxito de negocio. No deducir cantidad de fallos multiplicando una tasa redondeada de consola. Abrir JSON para conteos exactos y documentar denominador. El setup puede incluir 409 de cuenta ya existente: anotarlo aparte de la caída. `catalog_error_rate` mezcla muestras de login y catálogo; no usar su denominador como total HTTP.

P95 HTTP global (`http_req_duration`) y P95 listado/detalle no son lo mismo. Ninguna métrica existente calcula recovery automáticamente; **NO IMPLEMENTADO: medidor automático de recuperación**. El cronómetro y timestamps son evidencia manual.

## 11.7 Qué decir

“Durante tres minutos enviamos tráfico por el frontend. Detuvimos esta réplica en el worker y Swarm creó una nueva. Medimos [requests], [errores], [P95] y [segundos de recuperación]. La otra réplica [mantuvo/no mantuvo] servicio según la salida. Esto prueba respuesta a caída de un backend, no alta disponibilidad de MySQL”. No decir “cero errores” si no lo demuestra la corrida.

## 11.8 Si matar una réplica tumba todo

1. ¿Eran dos tasks actuales en dos nodos? Revisar §8.10.
2. ¿BASE_URL es frontend Swarm o un contenedor Compose independiente? Revisar puertos y servicio.
3. ¿Worker sigue Ready? Revisar node ls; caída de PC es escenario diferente al kill.
4. ¿Frontend healthy pero readiness falla? Revisar DNS/proxy/backend y MySQL.
5. ¿MySQL cayó? No seguir matando contenedores: es dependencia única.
6. ¿Nodo remoto no puede usar overlay? §18, puertos UDP/NAT; TCP 2377 no basta.
7. Conservar JSON con fallos y pasar al siguiente bloque. No prometer recuperación que no ocurrió.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal de control, raíz; diagnóstico de solo lectura.**
```bash
docker node ls
docker stack ps --no-trunc transformers
docker service logs --tail 80 transformers_frontend
docker service logs --tail 80 transformers_backend
docker service logs --tail 80 transformers_mysql
docker network inspect transformers_internal
```

# 12. Limitación de MySQL / disponibilidad

**MySQL: una réplica, volumen local y fijada a un manager por ID. NO HAY HA NI REPLICACIÓN DE BASE DE DATOS.** Si cae ese computador, los backends pueden seguir vivos pero perder readiness y operaciones. El volumen no migra al worker; cambiar MYSQL_NODE_ID puede crear otro volumen vacío en otro nodo.

Un solo manager es además un punto de fallo del control; que alguna task existente siga ejecutándose no garantiza nuevas decisiones de scheduling. `start-first` en frontend/backend ayuda durante actualización, requiere recursos temporales para otra réplica; MySQL usa `stop-first` para no tener dos escritores y `failure_action: pause`.

Liveness backend comprueba el proceso; readiness agrega DB. Routing mesh no consulta el endpoint readiness para cada petición. Sesiones JDBC están compartidas por MySQL, que sigue siendo dependencia única.

No se encontró SAD/documento de decisiones original en los archivos versionados para cotejar una promesa mayor. Si las diapositivas prometen HA completa de BD, esa afirmación **no está implementada por stack.yml**. Un rollback de imagen no revierte migraciones Flyway.

# 13. CI/CD — demostración en vivo

Hay **dos archivos workflow**, no un tercero separado para CD:

- `.github/workflows/backend-ci.yml`: **Backend CI and GHCR**, trigger push a todas las ramas. Jobs `validate`, `build`, `publish`, `frontend-e2e`, `deploy`.
- `.github/workflows/build-marketplace-launcher.yml`: **Build Marketplace launcher**, trigger manual `workflow_dispatch`, job `launcher`.

**OBSERVADO durante la auditoría:** [run del SHA actual 35717326168](https://github.com/TransformersAS/Transformers-AS/actions/runs/35717326168) seguía `in_progress` al cierre (05:59 Colombia): **Maven verification success**, **Frontend E2E with Playwright success**, **Build and publish to GHCR in_progress**, build no-main skipped; deploy todavía no aparecía en el listado de jobs. No se afirma pipeline completo verde de main. [Run histórico 35711629946](https://github.com/TransformersAS/Transformers-AS/actions/runs/35711629946), SHA `e259729e9d57cf87a533128d777f1530b0da910c`, fue success con validate, frontend-e2e y publish; su listado de jobs **no contiene deploy**. No usar ese verde como prueba de CD actual.

La API de runners del repositorio devolvió **total_count=0**. No se verificó un runner de organización ni su asignación/disponibilidad; la ausencia de runners de repo y de ejecución deploy exitosa impide afirmar CD operativo. El código sí lo declara: `runs-on: [self-hosted, swarm-manager]`, environment `production`, `needs: publish`, llama `bash deploy.sh`. No hay login GHCR en ese job: depende de credenciales preconfiguradas en el runner.

| Etapa requerida | Implementada | Job/step real | Qué mostrar |
|---|---|---|---|
| Java 21 | SÍ | validate / Set up Java 21 | Versión del log |
| Backend compile/tests | SÍ | validate / Verify backend, clean verify | Resultado Maven |
| Testcontainers/MySQL | SÍ | Check Docker for Testcontainers + suite backend | Docker y tests concretos |
| JaCoCo normal 95 % | SÍ | POM + Preserve coverage and test reports | Artifact backend-reports-SHA |
| Integración exclusiva 100 % en CI | NO | CI no activa integration-coverage | Mostrar diferencia con perfil local |
| Node 22 / npm | SÍ | frontend-e2e / npm ci | Instalación real |
| Frontend build | SÍ | frontend-e2e y Dockerfile | npm run build |
| Playwright | SÍ | Run Playwright E2E | Chrome y reporte |
| E2E real browser/backend/MySQL | SÍ | Run real CU-08 browser/backend/MySQL E2E | scripts/cu08-real-e2e.sh y resultado |
| Build Docker no-main | SÍ | build | Ambas imágenes sin publicar |
| GHCR en main | SÍ | publish | Backend y frontend multiarch |
| Tags | SÍ | publish | latest y sha-SHA completo; label OCI revision |
| CD | PARCIAL | deploy / Run deploy.sh against the real Swarm cluster | Declarado; operación actual no acreditada |
| Self-hosted | PARCIAL | labels self-hosted, swarm-manager | API repo sin runners; revisar Settings |
| Health/convergencia | SÍ, en script | deploy.sh | Si CD corre, ver resultado del script |
| Smoke del stack desplegado | PARCIAL | probes HTTP en deploy.sh | No suite funcional completa post-deploy |
| Rollback | PARCIAL | update_config de frontend/backend | Política declarada; no ensayo actual; no rollback DB |
| Launcher smoke en Actions | NO | launcher job solo publish .NET | smoke.py existe, no se invoca allí |

**Dependencias importantes:** publish depende solo de validate; frontend-e2e corre en paralelo. deploy depende solo de publish. **Un fallo Playwright no bloquea por dependencia la publicación/despliegue** si validate/publish pasan. No se encontró trigger pull_request en el YAML actual, aunque documentación o nombres de ramas antiguos lo sugieran.

**DÓNDE HACER ESTO — 💻 PC MANAGER, navegador GitHub.** Abrir Repo → Actions → Backend CI and GHCR → ejecución del SHA → expandir jobs. Mostrar validate, artifacts, frontend-e2e, publish y deploy con su estado real. Si deploy está queued, explicar el runner pendiente, no llamarlo deploy exitoso. Settings → Actions → Runners y Settings → Environments → production solo si se tiene permiso, evitando mostrar secretos.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz, consultas read-only para actualizar la foto.**
```bash
gh run list --repo TransformersAS/Transformers-AS --branch main --limit 8
gh run view 35717326168 --repo TransformersAS/Transformers-AS --json headSha,status,conclusion,jobs,url
gh api repos/TransformersAS/Transformers-AS/actions/runners --jq '{total_count,runners:[.runners[]|{name,status,busy}]}'
```

📸 Guardar SHA, run y jobs. Si la API devuelve 403/404, no inferir estado: indicar falta de acceso. No disparar workflows ni pushes solo para fabricar evidencia en el minuto final.

# 14. Cómo mostrar GHCR

💻 PC MANAGER, navegador: repositorio → Packages, o perfil de organización → Packages. Buscar `transformers-as-backend` y `transformers-as-frontend`; abrir versión `sha-770f3c6da98a1885b69bf43c606a60986b2468e6` **solo cuando esté publicada**. Mostrar tag, plataformas y digest. La label `org.opencontainers.image.revision` se configura con github.sha.

El tag SHA identifica intención de versión, pero una etiqueta puede moverse; registrar el digest resuelto del servicio. Las dos imágenes tienen digests diferentes. La observación inicial de `manifest unknown` no demuestra acceso/publicación futura.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz; después de un despliegue real de transformers.**
```bash
docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' transformers_backend
docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' transformers_frontend
```

🟢 Esperado: repositorio GHCR correcto, tag fijado y digest cuando se resolvió. 📸 Capturar Packages y referencias de servicio. Si solo existe la imagen antigua, nombrar ese SHA y no atribuirlo a main.

# 15. Workflow del launcher

💻 PC MANAGER, navegador → Actions → **Build Marketplace launcher** → [ejecución 35715767674](https://github.com/TransformersAS/Transformers-AS/actions/runs/35715767674).

**OBSERVADO:** success, SHA `856a52b312cef9d16fab8b83bf86f3b46725c13c` (anterior a main auditada). Job `launcher`: `windows-latest`, setup .NET 8, `dotnet publish`, Release, `win-x64`, self-contained, single file, bibliotecas nativas extraíbles; artifact **Marketplace-win-x64**, ruta generada `artifacts/launcher/Marketplace.exe`.

El exe versionado en raíz fue inspeccionado como PE32+ x86-64. No se comparó su hash con el artifact descargado: no certificar que son binariamente idénticos. No se requiere SDK .NET para ejecutar el self-contained, pero sí Docker Desktop y entrega completa.

Esto demuestra **construcción en Windows y artifact**, no doble clic, extracción del runtime, firewall, arranque Docker ni apertura de navegador en Windows. `launcher/tests/smoke.py` existe y prueba manejo de infraestructura con Docker falso; no es prueba del sistema end-to-end y el workflow no lo llama. 📸 Capturar job y artifact con SHA.

# 16. Smoke / health por ambiente

| Probe | Qué comprueba | No demuestra por sí solo |
|---|---|---|
| Frontend `/healthz` | Nginx responde JSON UP | Que API/DB funcionen |
| Backend `/actuator/health/liveness` | Proceso vivo | DB disponible |
| Backend `/actuator/health/readiness` | readinessState + DB | Todos los flujos funcionales |
| Frontend `/api/actuator/health/readiness` | Proxy Nginx → backend → readiness | Correctitud de todos los CUs |
| MySQL healthcheck | SELECT 1 con usuario/base de aplicación | HA de datos |

Backend Dockerfile: curl liveness cada 10 s, timeout 5 s, start_period 60 s, retries 5. Demo lo sobrescribe por readiness cada 5 s, timeout 5 s, start_period 60 s, retries 60. Frontend: wget healthz cada 10 s, timeout 5 s, start_period 10 s, retries 3. MySQL: cada 10 s, timeout 5 s, start_period 60 s, retries 10.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows launcher, PowerShell, cualquier carpeta; puertos del exe, NO del Compose observado en Mac.**
```powershell
curl.exe --fail --max-time 5 http://127.0.0.1:4300/healthz
curl.exe --fail --max-time 5 http://127.0.0.1:4300/api/actuator/health/readiness
curl.exe --fail --max-time 5 http://127.0.0.1:8080/actuator/health/liveness
curl.exe --fail --max-time 5 http://127.0.0.1:8080/actuator/health/readiness
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal, cualquier carpeta; Compose realmente observado.**
```bash
curl --fail --max-time 5 http://127.0.0.1:4300/healthz
curl --fail --max-time 5 http://127.0.0.1:4300/api/actuator/health/readiness
curl --fail --max-time 5 http://127.0.0.1:18080/actuator/health/liveness
curl --fail --max-time 5 http://127.0.0.1:18080/actuator/health/readiness
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER o WORKER como cliente, Bash, cualquier carpeta; únicamente Swarm preparado con puertos 18000/18090 y candidata reconfirmada.**
```bash
curl --fail --max-time 5 http://192.168.40.13:18000/healthz
curl --fail --max-time 5 http://192.168.40.13:18000/api/actuator/health/readiness
curl --fail --max-time 5 http://192.168.40.13:18090/actuator/health/liveness
curl --fail --max-time 5 http://192.168.40.13:18090/actuator/health/readiness
```

En PowerShell usar `curl.exe` con esas mismas URLs. 🟢 Esperado HTTP 200 y UP. Solo las consultas de la tabla §0 están observadas en auditoría; las del futuro stack 18000/18090 no se ejecutaron. No hay endpoint `/api/actuator/health/liveness` especial en nginx equivalente al proxy readiness: usar liveness directo del backend.

# 17. Plan B si Internet muere

| Parte | Si Internet cae pero LAN y motores siguen | Qué evidencia/acción honesta usar |
|---|---|---|
| Marketplace | Contenedores ya arrancados pueden seguir; servicios externos pueden fallar | GUI local y health. No relanzar build si no hace falta |
| Launcher frío | Puede necesitar bases, apt, Maven, npm y Docker registry | No garantizar arranque offline; usar sistema preparado |
| Performance | k6 local/imagen ya presente y dataset local pueden funcionar | JSON nuevos con URL/entorno; no se necesita GHCR durante carga |
| Availability | Reemplazo puede funcionar con imagen local en nodo elegible | Verificar de verdad; no asumir que una task sin imagen puede descargarla |
| Swarm deploy | deploy.sh real siempre hace pulls y resolve-image always | Puede fallar aunque otras tasks sigan; mostrar script + evidencia previa identificada |
| CI/CD / GHCR | No se puede consultar ni publicar remoto | Capturas/reportes descargados con SHA, fecha y run; no llamarlos ejecución en vivo |

Si **Wi-Fi/LAN cae**, los nodos pueden perder overlay/DB: es más grave que perder Internet. Mantener demo Compose local si ya está preparada; decir que distribución física no está disponible. No usar red móvil nueva sin verificar alcance entre motores. No inventar capturas previas: en esta auditoría solo se consultaron estados y documentos, no se generaron reportes de carga ni suite nueva.

# 18. Plan B si Swarm entre dos PCs no funciona

**Tiempo máximo de diagnóstico durante los 20 minutos: 60–90 s.** Si no se resuelve con una comprobación, mostrar evidencia parcial y continuar. Preparar topología alternativa requiere trabajo previo; no es tarea de esta guía de auditoría.

| Orden | Dónde / comando o pantalla | Esperado | Si falla |
|---|---|---|---|
| 1 IP | Ambos PCs, terminal, cualquier carpeta: comandos §3 | IP LAN actual y motor alcanzable | Sustituir candidata; no usar IP VM aislada |
| 2 Conectividad | Worker, terminal: ping/TCP §3.4 | Alcance tras init | Revisar aislamiento Wi-Fi/ruta antes de Docker |
| 3 Swarm state | Ambos, terminal: §8.2 | Manager true, worker false, active | No ejecutar leave/reset; identificar cluster correcto |
| 4 node ls | Manager, terminal: bloque abajo | Dos Ready/Active | Join no completado o nodo inaccesible |
| 5 Firewall | Windows: Get-NetFirewallProfile en §3; Mac ajustes Red/Firewall; Linux política administrada | Reglas entre nodos de confianza | Solicitar revisión acotada; no apagar seguridad completa |
| 6 Desktop | Ambos, docker context show / info §2 | CLI apunta al motor acordado | NAT/VM puede impedir cluster físico; plan B |
| 7 Puertos | Worker y manager, pruebas §3.4 | 2377 y 7946 TCP, además UDP operativo | TCP exitoso no valida 4789 UDP; revisar políticas/red |
| 8 Arquitectura | Ambos, manifests y version §8.7 | Imagen para cada arquitectura | No asumir emulación; usar SHA compatible declarando cambio |
| 9 GHCR | Ambos, manifest/login §8.7 | Tag existe y permiso | No confundir denied con manifest unknown |
| 10 Secrets | Manager, secret ls §8.8 | Tres nombres correctos | Archivo requerido debe existir en manager/runner; no exponerlo |
| 11 Overlay | Manager, network inspect abajo | Red transformers_internal | Red presente no prueba tráfico; readiness desde ambos PCs |
| 12 service ps | Manager, abajo | Running; errores ausentes | Pending: placement/recursos; Rejected: imagen/mount; Failed: logs |
| 13 Logs | Manager, abajo | Arranque estable | DB/migración/credenciales/proxy; conservar antes de actuar |

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz; stack PROPUESTO transformers, solo si existe.**
```bash
docker node ls
docker stack ls
docker stack services transformers
docker network inspect transformers_internal
docker stack ps --no-trunc transformers
docker service logs --tail 100 transformers_backend
docker service logs --tail 100 transformers_mysql
docker service logs --tail 100 transformers_frontend
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac auditado, terminal local, raíz; inspección del stack ACTUAL marketplace.**
```bash
docker stack services marketplace
docker service ps --no-trunc marketplace_backend
docker service logs --tail 100 marketplace_backend
docker service logs --tail 100 marketplace_mysql
```

No ejecutar comandos transformers sobre marketplace cambiando nombres al azar: primero decidir qué entorno se demuestra. El stack actual carece de frontend Swarm y corre SHA anterior. No se incluye comando de mutación para “repararlo”.

**Fallback local opcional, solo preparado con anticipación en motor apto:** `deploy.sh --local` existe y construye ambas imágenes; inicializa loopback únicamente si inactive, usa defaults 18000/18080. **No ejecutarlo en el Mac auditado como receta rápida:** 18080 ya está ocupado y el motor tiene stack existente. No equivale a dos PCs ni garantiza terminar dentro del tiempo. Mostrar configuración/script y el entorno ya sano es más honesto que fingir un cluster físico.

Discrepancias documentales verificadas: `docs/swarm.md` solo redirige a guía actual; `docs/despliegue/docker-swarm.md` abre diciendo que no hay CD remoto, pero luego describe CD y el YAML lo declara. También contiene un curl a 18090 en un ejemplo `--local` cuyo default real es 18080. Aquí 18090 se utiliza **solo como override explícito**. Los nombres transformers-local de ejemplos no son el stack observado marketplace ni el default transformers.

# 19. Plan B si el frontend no abre

1. 💻 PC navegador: confirmar URL/entorno de §0 y §16. localhost es ese PC, no el de Sofía.
2. 💻 PC que aloja Compose: ps frontend, luego healthz. 💻 Manager Swarm: service ps frontend.
3. Si healthz UP pero GUI antigua: recarga completa o ventana privada; frontend tiene configuración de service worker, evitar confundir caché con imagen recién desplegada.
4. Si readiness proxy falla y backend directo funciona: leer logs Nginx/DNS hacia backend; no reconfigurar proxy durante demo.
5. Si 4300 funciona solo local: es bind loopback declarado; no decir que debería abrir desde worker.
6. Capturar fallo; usar el entorno ya preparado alternativo identificando URL/SHA.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows launcher, PowerShell, raíz.**
```powershell
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml ps frontend
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml logs --tail 80 frontend
curl.exe --fail --max-time 5 http://127.0.0.1:4300/healthz
curl.exe --fail --max-time 5 http://127.0.0.1:4300/api/actuator/health/readiness
```

Para Swarm usar §18 y §16 en manager. No reiniciar Nginx a ciegas durante k6; invalidaría la comparación.

# 20. Plan B si el backend no arranca

1. Identificar qué backend falla (launcher, Compose Mac, transformers o marketplace).
2. Revisar task error/health y logs. Un fallo inicial Swarm mientras MySQL inicia puede reintentarse; reinicios repetidos después no son “normal”.
3. Confirmar MySQL healthy, DB_NAME/DB_USER correctos y secretos consistentes con volumen. No imprimir contraseñas.
4. Revisar logs de Flyway y causa raíz; no reparar checksum ni migraciones manualmente durante demo.
5. Revisar memoria/CPU, imagen compatible y main/tag. El demo runner puede abortar por fixture incompatible; no eliminar datos para forzarlo.
6. Si liveness UP y readiness DOWN, no afirmar “está bien”: DB/readiness sigue fallando.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows launcher, PowerShell, raíz.**
```powershell
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml ps
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml logs --tail 150 backend
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal, raíz; Compose que estaba activo al auditar.**
```bash
docker logs --tail 150 transformers-as-backend-1
docker stats --no-stream
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER del nuevo stack, Terminal, raíz; solo si transformers fue desplegado.**
```bash
docker service ps --no-trunc transformers_backend
docker service logs --tail 150 transformers_backend
```

📸 Guardar error sin datos personales; si sigue rojo, mostrar cobertura/CI y declarar indisponibilidad actual.

# 21. Plan B si MySQL no arranca

1. Revisar estado/logs y tiempo inicial de arranque. MySQL health hace SELECT 1 con usuario real, no solo ping al proceso.
2. Compose: comprobar 3307 libre. Swarm: MySQL no publica puerto, así que no buscar su salud con localhost:3307.
3. Swarm: comprobar manager fijado Ready/Active y placement por ID; no cambiar ID para “sacarlo del pending”.
4. Confirmar que archivo/secret coincide con contraseña inicial del volumen. Cambiar env no reinicializa MySQL existente.
5. Revisar espacio y memoria. Preservar datos; escalar diagnóstico fuera del bloque si hay corrupción o errores de permisos.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows launcher, PowerShell, raíz.**
```powershell
docker compose --env-file .env.demo -p marketplace-demo -f compose.yaml -f compose.demo.yaml logs --tail 100 mysql
docker volume inspect marketplace-demo_mysql_data
docker system df
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER del stack transformers, terminal, raíz.**
```bash
docker service ps --no-trunc transformers_mysql
docker service inspect --format '{{json .Spec.TaskTemplate.Placement.Constraints}}' transformers_mysql
docker service logs --tail 100 transformers_mysql
docker volume ls
docker system df
```

**No borrar volumen, no down -v, no recrear secrets con claves distintas, no editar tablas/migraciones a mano como primera solución.** La base es dependencia única: si falla, declarar que el backend no puede sostener operaciones.

# 22. Plan B si un puerto está ocupado

**DÓNDE EJECUTAR ESTO — 💻 PC Mac que alojará el servicio, Terminal local, cualquier carpeta.**
```bash
lsof -nP -iTCP:4300 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:3307 -sTCP:LISTEN
lsof -nP -iTCP:18000 -sTCP:LISTEN
lsof -nP -iTCP:18080 -sTCP:LISTEN
lsof -nP -iTCP:18090 -sTCP:LISTEN
docker ps --format '{{.Names}}\t{{.Ports}}'
docker service ls
```

**DÓNDE EJECUTAR ESTO — 💻 PC Windows que alojará el servicio, PowerShell local, cualquier carpeta.**
```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 4300,8080,3307,18000,18080,18090 } | Select-Object LocalAddress,LocalPort,OwningProcess
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 4300,8080,3307,18000,18080,18090 } | ForEach-Object { Get-Process -Id $_.OwningProcess }
docker ps --format '{{.Names}}\t{{.Ports}}'
docker service ls
```

**DÓNDE EJECUTAR ESTO — 💻 PC Linux que alojará el servicio, Bash local, cualquier carpeta.**
```bash
ss -lntp
docker ps --format '{{.Names}}\t{{.Ports}}'
docker service ls
```

Una salida vacía de lsof no basta para probar puerto disponible en routing mesh/Desktop: contrastar Docker services y acceso real. Compose aparece con binding en docker ps; Swarm con publicación en service ls; el launcher administra Compose, no un listener propio de frontend.

Si el dueño es una demo necesaria, conservarla y elegir otros puertos **mediante variables soportadas en deploy.sh antes del despliegue**, como 18000/18090 en esta guía. El exe fuerza los suyos: usar el Windows preparado o acordar detener la aplicación propietaria fuera de pruebas, no matar PID de Docker Desktop. Si ambos puertos propuestos están ocupados, no copiar la receta sin revisar todas las URLs.

# 23. Qué NO hacer en sustentación

- No ejecutar `docker system prune`, `docker compose down -v`, borrar volumen MySQL ni reseteos del dataset por impulso.
- No ejecutar `docker swarm leave --force`, retirar el stack existente ni cambiar MYSQL_NODE_ID para hacer desaparecer un error.
- No hacer commits, push, cambios de código/tests/workflows/infraestructura durante esta auditoría o para maquillar el resultado.
- No cambiar thresholds k6 ni saltar tests/gates para obtener verde.
- No afirmar main desplegada si la imagen usa otro SHA; no usar latest como prueba del commit.
- No presentar 2/2 en un nodo como dos computadores; no presentar frontend independiente como servicio Swarm.
- No matar MySQL, las dos réplicas o el daemon durante la prueba de un backend.
- No lanzar launcher, builds, Testcontainers y carga al mismo tiempo; compiten por recursos y el launcher recrea backend.
- No borrar `.env.demo` conservando su volumen; no imprimir secrets, tokens de join ni tokens GHCR.
- No ejecutar seeds contra datos importantes ni improvisar migraciones/SQL.
- No pegar Bash en PowerShell ni operar un daemon WSL creyendo que es el de Desktop.
- No afirmar que health prueba todos los CUs, que rollback revierte DB o que CI histórico acredita CD actual.

# 24. Secuencia exacta de los 20 minutos

Las horas siguientes son **duraciones desde que terminan las diapositivas**. Los entornos ya deben estar preparados. Mantener el mismo k6 cliente, dataset y URLs durante comparaciones.

| Minuto | Quién comparte / PC | Pantalla o acción | Resultado visible / frase de cierre | Transición |
|---|---|---|---|---|
| 00–01 | Vanessa, Windows si existe | Launcher ya listo y GUI; health §16 | “Este arranque usa Compose y base real local” | Entrar a flujos |
| 01–02 | Expositora funcional | GUI, inicio de flujo asignado | Backend responde | Mantener Docker quieto |
| 02–03 | Expositora funcional | Continuar flujo | Resultado de negocio | Persistencia |
| 03–04 | Expositora funcional | Refresco/consulta | Estado persistido | Cerrar flujos |
| 04–05 | Expositora funcional | Resultado final/captura | “Usamos los flujos asignados; estos proveedores son simulados” | Sofía comparte |
| 05–06 | Sofía, manager | Manifiesto y reporte ya generado o histórico | 81 clases actuales; alcance del reporte | Gate |
| 06–07 | Sofía | LINE/porcentaje/gate | “100 % es requisito, no resultado acreditado” | k6 |
| 07–08 | Sofía, cliente k6 | Iniciar 50 VUs §10.1 | Progreso | Esperar resumen |
| 08–09 | Sofía | Registrar salida 50, iniciar 100 §10.2 | P95/error/throughput 50 | Comparación |
| 09–10 | Sofía | 100 VUs corriendo | Carga visible | Resumen |
| 10–11 | Sofía | Registrar 100/captura | PASS/FAIL real de ambos P95 | Availability |
| 11–12 | Sofía + Vanessa | Verificar 2/2 y nodos, iniciar k6 3m | Tráfico estable | Elegir task |
| 12–13 | Vanessa worker, Sofía controla | Kill UNA réplica §11.4, cronómetro | Task fallida identificada | Recuperación |
| 13–14 | Sofía manager | Nueva task, salud en cada nodo | 2/2 y recovery medido | Esperar k6 |
| 14–15 | Sofía | Resumen availability y captura | Errores/P95/throughput reales | Deployability |
| 15–16 | Sofía manager | Nodos, tasks en PCs, un script | Distribución física o limitación explícita | Repetición script |
| 16–17 | Sofía | Repetir deploy.sh previamente preparado | Inicio/resultado; no build frío | Health |
| 17–18 | Sofía | Convergencia y readiness, o estado en curso | “Este es el estado observado” | GitHub |
| 18–19 | Sofía navegador | Actions del SHA, jobs reales | CI real y artifact GHCR | CD/launcher |
| 19–20 | Sofía | deploy/runner y launcher artifact | “CD [estado]; Windows build no es doble clic” | Guardar hoja de resultados |

Si una corrida excede tiempo, no recortar silenciosamente duración: registrar corrida parcial y mostrar evidencia previa identificada si existe. Si Swarm no estuvo preparado, reemplazar 11–18 por ensayo local/evidencia parcial explícita, sin atribuir cumplimiento de distribución.

# 25. Checklist “profe está mirando”

Marcar solo después de observar, no porque esté declarado en YAML.

- [ ] Frontend y URL del entorno elegido visibles.
- [ ] Backend y DB reales identificados; límites de simuladores declarados.
- [ ] Flujo E2E y persistencia mostrados.
- [ ] Tests/reporte identificados con SHA y tipo de suite.
- [ ] Coverage y gate mostrados sin afirmar 100 % inexistente.
- [ ] 50 VUs y 100 VUs ejecutados o limitación declarada.
- [ ] P95 listado, P95 detalle, error rate y throughput anotados.
- [ ] Dos nodos en dos computadores comprobados, o requisito pendiente declarado.
- [ ] Dos backend replicas y placement comprobados.
- [ ] Una réplica backend interrumpida, task nueva y salud comprobadas.
- [ ] Continuidad/fallos del servicio cuantificados, recovery anotado.
- [ ] deploy.sh y stack.yml actuales mostrados.
- [ ] CI, CD y GHCR mostrados con estados reales.
- [ ] Health separado de funcionalidad y HA.
- [ ] Evidencias guardadas sin secretos.

# 26. Resultados que debemos anotar en vivo

No rellenar con números de ejemplo.

| Métrica / contexto | Resultado |
|---|---|
| Fecha / hora / operador | |
| SHA del código | |
| Tags y digests desplegados | |
| Entorno / stack / URL medida | |
| IP manager / IP worker / motores | |
| NodeIDs y PCs correspondientes | |
| Nodes Ready | |
| Backend replicas y distribución | |
| Versión k6 / arquitectura / recursos | |
| Dataset: usuarios / productos / pedidos | |
| Performance 50 VUs P95 listado / detalle | |
| Performance 50 VUs catalog_error_rate / http_req_failed | |
| Performance 50 VUs throughput catálogo / HTTP req/s | |
| Performance 50 VUs exit code / thresholds | |
| Performance 100 VUs P95 listado / detalle | |
| Performance 100 VUs catalog_error_rate / http_req_failed | |
| Performance 100 VUs throughput catálogo / HTTP req/s | |
| Performance 100 VUs exit code / thresholds | |
| Availability total HTTP / exitosos / fallidos | |
| Availability catalog_error_rate / checks | |
| Availability P95 listado / detalle / HTTP | |
| Availability throughput catálogo / HTTP req/s | |
| Task antigua / nodo / container ID | |
| T0 kill / T1 nueva task healthy | |
| Recovery time / resolución de medición | |
| Coverage integración: SHA / clases / tests | |
| Coverage LINE covered / missed / total / % | |
| Gate integración y resultado | |
| CI run / estado | |
| CD job / runner / estado | |
| Limitación o fallo observado | |

# 27. Evidencias / capturas

Carpeta **propuesta a crear** `evidencias-sustentacion/` en raíz, no evidencia preexistente. Los JSON se generan solo cuando se ejecutan los comandos. Guardar capturas manualmente con estos nombres:

| Momento exacto | Nombre propuesto | Qué debe verse |
|---|---|---|
| Antes del reloj, git/IP | `00-sha-contexto.png` | SHA, fecha, PC/IP |
| Launcher listo | `01-launcher.png` | Mensaje listo y URL, sin claves |
| Tras health | `02-health.png` | URL y UP del entorno correcto |
| Functional terminado | `03-funcional.png` | Resultado/refresco, sin datos sensibles |
| Coverage explicado | `04-coverage.png` | LINE, alcance, gate y versión |
| node ls con dos PCs | `05-nodos.png` | Dos IDs Ready/Active y asociación física |
| service ps antes de kill | `06-placement-2-replicas.png` | Una task por nodo |
| Inmediatamente después kill | `07-task-caida.png` | Task ID anterior y fallo/timestamp |
| Nueva task healthy | `08-task-recuperada.png` | Nuevo ID, 2/2 y salud |
| Resumen 50 VUs | `09-k6-50.png` + `catalogo-50.json` | P95/error/throughput/thresholds |
| Resumen 100 VUs | `10-k6-100.png` + `catalogo-100.json` | Ídem, VUS=100 |
| Resumen availability | `11-availability.png` + `availability-k6.json` | Requests/errores/P95 y cronómetro anotado |
| deploy.sh terminado | `12-deploy.png` | Script único y convergencia, o fallo explícito |
| Actions | `13-ci-cd.png` | Run, SHA, jobs, queued/fail/success reales |
| Packages | `14-ghcr.png` | Ambos paquetes, tag/digest |
| Launcher workflow | `15-launcher-build.png` | Windows runner, SHA y artifact |

No capturar join token, `.env`, passwords ni GitHub token. No dibujar un PASS sobre salida FAIL. Acompañar capturas históricas con fecha y SHA; si no existe evidencia de cierta prueba, escribir “no ejecutada”.

# 28. Preguntas técnicas probables sobre la demo

| Pregunta | Respuesta breve basada en esta implementación |
|---|---|
| ¿Por qué Swarm? | El repo describe estado deseado en stack.yml y lo despliega con un script. Swarm mantiene réplicas y reemplaza tasks fallidas; el cumplimiento en dos computadores requiere comprobar placement real. |
| ¿Qué es una réplica? | Una instancia del servicio ejecutada como task/contenedor. Aquí se piden dos frontend y dos backend; dos instancias pueden caer en el mismo computador. |
| ¿Qué ocurre si cae una? | El scheduler intenta crear otra según restart policy. La prueba mide errores y tiempo real; no hay garantía de cero errores ni de DB disponible. |
| ¿Quién balancea? | Nginx sirve frontend y reenvía API al nombre backend. El descubrimiento/VIP de servicios y routing mesh de Swarm distribuyen conexiones según la ruta usada. |
| ¿Qué es routing mesh? | Publica el puerto ingress en los nodos y encamina hacia tasks. No significa que cada nodo tenga una réplica ni que consulte readiness HTTP para cada request. |
| ¿Por qué MySQL tiene una réplica? | El stack usa volumen local fijado a un manager para mantener un único escritor. No implementa replicación ni failover DB; es una limitación explícita. |
| ¿Qué significa P95? | El 95 % de las muestras cae en o por debajo de ese tiempo. Aquí se mide listado y detalle por separado, con umbral de 3 s para cada uno. |
| ¿Qué mide throughput? | La tasa de trabajo completado. catalog_throughput cuenta detalles exitosos por segundo; http_reqs mide requests HTTP y tiene otro denominador. |
| ¿Qué significa error rate? | Proporción de muestras consideradas error por cada métrica. El rate de catálogo incluye fallos de negocio/login y no es idéntico a http_req_failed. |
| ¿Por qué 50/100 VUs? | 50 es la referencia de comparación y 100 la carga asociada al ASR citado de catálogo. Son VUs concurrentes con cookie jars separados, pero el script usa una misma cuenta configurada. |
| ¿Qué prueba Testcontainers? | Arranca MySQL real para suites que integran persistencia y aplicación. No acredita infraestructura del salón ni proveedores reales si la suite usa WireMock. |
| ¿Qué significa integration coverage? | Líneas de producción ejecutadas por la selección exclusiva de integración. El perfil no suma cobertura unitaria y exige cero líneas missed; 100 % no está acreditado. |
| ¿Qué hace deploy.sh? | Verifica motor/imágenes, prepara secrets y despliega stack, luego espera réplicas y probes. No crea un cluster físico real por sí solo ni configura la red de los PCs. |
| ¿Qué hace stack.yml? | Declara imágenes, servicios, red overlay, volumen, puertos, secrets, réplicas y políticas. La distribución de frontend/backend es preferencia spread y se debe verificar. |
| ¿Qué hace Docker secret? | Entrega valores a tasks autorizadas mediante archivos, evitando poner claves en configuración de servicio. No protege contra el administrador del daemon ni rota la contraseña de un volumen MySQL automáticamente. |
| ¿Qué es GHCR? | Registro donde el workflow publica backend y frontend. Cada nodo necesita acceso al artefacto compatible; latest no identifica de forma estable un commit. |
| ¿Por qué tags SHA? | Relacionan imagen con github.sha del build. Guardar digest mejora la trazabilidad porque una etiqueta puede moverse. |
| ¿Qué hace CI? | Ejecuta Maven, JaCoCo normal, frontend/Playwright y builds; en main publica imágenes. El perfil de integración 100 % no se ejecuta en el YAML actual. |
| ¿Qué hace CD? | El job declarado llama deploy.sh en un runner self-hosted del manager. Está implementado en YAML, pero no se acreditó runner operativo ni deploy exitoso del SHA actual. |
| ¿Health/liveness/readiness? | Health agrupa estado; liveness comprueba proceso, readiness agrega DB. Nginx healthz solo verifica frontend, por eso también se consulta readiness a través del proxy. |
| ¿Qué demuestra el launcher? | Automatiza arranque Compose local, prepara fixture demo y abre navegador en Windows. El workflow exitoso demuestra construcción del exe, no su ejecución manual en el PC final. |
| ¿Qué NO demuestra el launcher? | No forma Swarm, no distribuye en dos PCs, no prueba CI/CD ni HA. Un sistema abierto por el exe sigue siendo un despliegue Compose de un host. |
| ¿Rollback protege los datos? | Frontend/backend tienen rollback de actualización declarado. Flyway y cambios en DB no se revierten por cambiar imagen; MySQL tiene política distinta y volumen persistente. |

# 29. Comandos de emergencia — cheat sheet

**Usar solo el bloque del entorno que realmente existe.** Los comandos transformers asumen receta preparada 18000/18090; marketplace es el stack antiguo observado. Las variables k6 se preparan privadamente en §10.

## Red

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Mac, Terminal, cualquier carpeta; en0 solo si se confirmó interfaz.**
```bash
ipconfig getifaddr en0
docker info --format '{{.Swarm.NodeAddr}}'
```

**DÓNDE EJECUTAR ESTO — 💻 PC WORKER Windows, PowerShell, cualquier carpeta.**
```powershell
Test-NetConnection 192.168.40.13 -Port 2377
```

## Docker

**DÓNDE EJECUTAR ESTO — 💻 AMBOS PCs, terminal local, cualquier carpeta.**
```text
docker context show
docker info --format '{{.OSType}} {{.Swarm.LocalNodeState}} {{.Swarm.NodeID}}'
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
```

## Swarm

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz.**
```bash
docker node ls
docker stack ls
```

## Services

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER del stack transformers PREPARADO, terminal local, raíz.**
```bash
docker stack services transformers
docker service ps --no-trunc transformers_backend
docker service ps --no-trunc transformers_frontend
```

## Logs

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER de transformers, terminal local, raíz.**
```bash
docker service logs --tail 80 transformers_backend
docker service logs --tail 80 transformers_mysql
```

## k6

**DÓNDE EJECUTAR ESTO — 💻 PC cliente de carga, MISMA terminal Bash de §10, raíz, URL/cuenta exportadas.**
```bash
k6 run -e VUS=100 -e DURATION=1m --summary-export evidencias-sustentacion/catalogo-100.json scripts/k6/catalog-browse.js
```

## Health

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER/WORKER cliente, Bash, cualquier carpeta; Swarm preparado y candidata verificada.**
```bash
curl --fail --max-time 5 http://192.168.40.13:18000/healthz
curl --fail --max-time 5 http://192.168.40.13:18000/api/actuator/health/readiness
```

## Launcher

**DÓNDE EJECUTAR ESTO — 💻 PC Windows launcher, PowerShell, raíz; el exe arranca/recrea demo, usar solo fuera de flujos/carga.**
```powershell
.\Marketplace.exe
```

## GitHub

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal local, raíz; lectura, no dispara ejecución.**
```bash
gh run view 35717326168 --repo TransformersAS/Transformers-AS
gh run view 35715767674 --repo TransformersAS/Transformers-AS
```

## Validación de esta guía y límites de la auditoría

Se contrastaron rutas versionadas, ambos Compose, stack/deploy, Dockerfiles, nginx, propiedades Spring, launcher, k6, manifiesto de integración y workflows. Se ejecutaron **solo lectura**: estado Git/remoto, auditor de 81 clases, `bash -n deploy.sh`, consultas Docker/GitHub y probes indicados en §0. No se ejecutaron comandos de init/join/deploy/kill/pull/seed que aparecen como recetas.

Los paths `evidencias-sustentacion/`, JSON k6, logs de launcher y reportes `target/` son **salidas propuestas o generadas por sus herramientas**, no se afirma que estén presentes. Se verificó ausencia del XML JaCoCo local. Las rutas de Windows y tokens marcados REEMPLAZAR son placeholders, no archivos/secretos encontrados. Los enlaces internos apuntan a documentos existentes; los nombres de imágenes/workflows/endpoints se tomaron del código actual. Los resultados GitHub/runtime cambian: actualizar consultas antes de hablar.

**Cinco fallos más probables:** (1) Docker Desktop/NAT impide overlay entre PCs; (2) puertos/SHA/stack mezclados hacen medir otro sistema; (3) imagen del SHA todavía no publicada, acceso GHCR o arquitectura; (4) catálogo vacío/cuenta no verificada invalidan k6; (5) launcher Windows/build frío o recursos compartidos agotan tiempo. Además, coverage 100 % no acreditado y CD sin runner verificado son brechas de entrega aunque la demo funcional abra.

# 30. SI SOLO PUEDES LEER UNA PÁGINA, LEE ESTO

1. **Antes del reloj:** dos motores alcanzables o declarar fallback; imágenes del SHA, puertos y datos listos; cuenta verificada; reporte y capturas con versión; roles/terminales acordados. No cabe prepararlo todo en 20 min.
2. **Launcher — Vanessa, Windows:** exe junto a ambos Compose y fuentes completas; Docker Linux listo; doble clic → `http://localhost:4300`. Log `launcher-logs/marketplace.log`. El exe no corre en Mac ni despliega Swarm.
3. **Swarm — Sofía manager:** IP candidata 192.168.40.13 era del Mac; motor observado anuncia 192.168.65.3 y tiene UN nodo. Stack real observado `marketplace`, backend antiguo 2/2, mysql 1/1, sin frontend Swarm. No llamarlo dos PCs ni main desplegada.
4. **Deploy:** seguir §8 solo tras red/GHCR/puertos verificados; receta nueva `transformers`, frontend 18000, backend 18090, mysql sin publicar. Configuración previa de §8.9; script único debajo. No forzar salida del Swarm existente.
5. **Performance:** §10, mismo frontend/cuenta, 50 VUs 1m y 100 VUs 1m. Catálogo listado y detalle P95 ≤3 s, error <2 %. Guardar ambos JSON. Catálogo vacío/login fallido no acreditan performance.
6. **Availability:** §11, 2/2 en dos PCs; k6 3m; identificar task y contenedor; matar UNA en su nodo, cronómetro, nueva task healthy y 2/2. No matar MySQL. Anotar errores, no prometer cero.
7. **Coverage/CI/CD:** histórico 97,216671 % con 80 clases; main tiene 81, porcentaje nuevo no verificado. CI al cierre: Maven y E2E success, publicación en curso; CD declarado, cero runners del repo consultados; launcher build histórico sí success. Mostrar estados reales.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, raíz; primer comando SOLO con preparación completa de §8.9; los demás son lectura del stack propuesto.**
```bash
bash deploy.sh
docker node ls
docker stack services transformers
docker service ps --no-trunc transformers_backend
curl --fail --max-time 5 http://192.168.40.13:18000/api/actuator/health/readiness
```

**NO HACER:** prune, down -v, borrar .env.demo/volúmenes, leave --force, cambiar thresholds, push, matar DB, inventar resultados. Si falla >90 s: capturar, decir qué faltó, usar entorno/evidencia parcial identificado y continuar.
