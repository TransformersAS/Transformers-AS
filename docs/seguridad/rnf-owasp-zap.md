# RNF-008 — OWASP ZAP automático, P0

## Objetivo y alcance académico

Implementar evidencia reproducible de DAST pasivo sobre el Marketplace real en
un ambiente descartable. Respaldar parcialmente RNF-008 y observaciones de
ASR-001 (identidad/acceso), ASR-002 (datos sensibles) y ASR-003 (auditoría).
No certifica esos ASR completos ni los casos de uso pendientes del equipo.

ZAP no sustituye SCA: esta implementación **no analiza CVE de dependencias**.
Tampoco sustituye las suites funcionales de roles, propiedad, sesiones y CSRF.
El requisito de no dejar vulnerabilidades críticas conocidas sin justificar
necesita además SCA, revisión y seguimiento antes de cada entrega.

## Arquitectura del ambiente

```text
Runner local / GitHub Actions (Docker local, nunca Swarm)
  └─ proyecto nuevo rnf-zap-<12 caracteres aleatorios>
       ├─ red target: internal=true
       │    ├─ ZAP fijado por digest + driver Python
       │    │    └─ API/proxy 127.0.0.1:8090 SOLO dentro de su contenedor
       │    ├─ frontend Angular compilado + Nginx real :80
       │    └─ backend Spring Boot real :8080
       └─ red database: internal=true
            ├─ backend
            └─ MySQL 8.4.11, /var/lib/mysql en tmpfs
```

No hay puertos publicados, red externa, volumen persistente ni secretos de
producción. El proxy/API ZAP no es accesible desde otros contenedores. La red
interna impide egreso de los servicios en ejecución hacia proveedores externos.
La descarga de dependencias/imágenes ocurre antes, durante construcción/pull.

`compose.security.yaml` es independiente: no se combina con `compose.yaml`,
`compose.e2e.yaml` ni los ambientes de otros CU. No declara `container_name`
ni subred fija. No usa interpolaciones personales ni `env_file`; el runner pasa
`--env-file /dev/null` y un entorno de subprocess acotado. Rechaza Docker remoto.

Backend: demo general apagada, pagos mock y recuperación apagados, tokens de
Mercado Pago/Gemini vacíos, SMTP sin configurar, push/cola apagados y logística
simulada sin polling. Estos controles complementan la prohibición de egreso.
No se deshabilitan Spring Security, CSRF, autorización ni validación Flyway.

El runner exige un proyecto sin recursos previos, inspecciona redes, puertos y
healthchecks antes del scanner y verifica las versiones Flyway contra los
archivos de migración vigentes. Si falla una guarda, no inicia ZAP.

## Secuencia de GitHub Actions

```text
push a feature/rnf-seguridad-zap o main / workflow_dispatch
  → checkout de github.sha, sin persistir credenciales
  → comprobar Docker/Compose
  → tests de guardas, cobertura, sanitización y política
  → validar Compose y exclusividad
  → construir backend/frontend de esta revisión; pull de ZAP por digest
  → MySQL healthy → backend readiness con DB → frontend/proxy healthy
  → verificar Flyway y base nueva; insertar fixtures exclusivas
  → ZAP modo safe, tráfico GET permitido y análisis pasivo
  → comprobar contratos, identidad, mensajes y URLs en ZAP
  → generar HTML/JSON sanitizados y evaluar riesgos
  → finally: down del proyecto propio, volúmenes e imágenes locales propias
  → limpieza de respaldo con always()
  → upload-artifact con always(), incluso si hay alertas o error técnico
```

Workflow: `.github/workflows/security-zap.yml`, nombre **OWASP ZAP Security Scan**.
Runner `ubuntu-24.04`, permiso `contents: read`, sin publicación de issues,
comentarios, sitios, imágenes ni despliegues. No se modifican los workflows
funcionales. Estos pueden seguir ejecutándose por sus disparadores existentes.

## Decisión: análisis pasivo controlado

Esta versión implementa **análisis pasivo de tráfico controlado con ZAP**, no
ejecuta el comando empaquetado `zap-baseline.py`. Consulta y conserva su ayuda
real (`-h`) en la imagen seleccionada, pero no usa su spider. El JSON lo declara
con `packaged_baseline_executed: false`.

La decisión evita descubrimiento insuficiente en Angular y solicitudes de
navegación no previstas. El driver envía únicamente GET de una allowlist,
sin redirects automáticos, sin ejecutar JavaScript y sin seguir otros dominios.
Activa y comprueba `core.mode=safe`; nunca invoca activeScan, Full Scan ni API
Scan. Espera que termine la cola pasiva antes de obtener las alertas.

Baseline combina descubrimiento y análisis pasivo; Full Scan añade ataques
activos, mayor duración y riesgo de modificar datos. Full Scan queda fuera de
esta fase. Si se incorpora en el futuro requerirá otra autorización y ambiente
descartable; no se ejecutará sobre producción, demo compartida o terceros.

Imagen ZAP multi-arquitectura fijada:

```text
ghcr.io/zaproxy/zaproxy@sha256:9624669db72f54d86c4b46a7064518bd9bf79d8c61a4c31befa9923489f328af
```

`metadata.json` conserva el digest solicitado, ID de imagen, RepoDigests,
versiones Docker/Compose, revisión CI, fecha UTC y duración. El reporte conserva
la versión real del motor. Backend/frontend se construyen de los archivos de
la ejecución; sus imágenes se identifican en `isolation.json`. Las imágenes
base de sus Dockerfiles existentes no se fijan nuevamente en esta fase.

## Fixtures y cobertura efectiva

`scripts/security/seed.sql` se aplica después de Flyway, solo si cuentas y
productos están vacíos. Inserta un comprador ficticio confirmado, rol
COMPRADOR, perfil ficticio, carrito vacío, dirección ficticia y un producto
con variante e imagen propia. La credencial de fixture no es productiva y no
se publica en los artefactos. No crea pagos, pedidos ni vulnerabilidades.

Roles de esta versión: **anónimo y COMPRADOR**. No cubre vendedor, soporte ni
administrador, ni pruebas de acceso cruzado entre dos cuentas.

| Tráfico observado por ZAP | Validación |
|---|---|
| `GET /` | HTML Angular con `app-root` |
| JS/CSS/iconos propios referidos directamente en el HTML | 200, contenido no vacío y no HTML |
| `GET /api/products` | Lista con el producto de fixture |
| `GET /api/products/1` | ID y variantes esperados |
| `GET /api/reviews/products/1` | Lista JSON; fixture sin reseñas |
| `GET /api/reviews/products/1/summary` | Resumen del producto correcto |
| `GET /api/auth/me` autenticado | Correo de fixture y rol COMPRADOR |
| `GET /api/cart` | Contrato `items` |
| `GET /api/favorites` | Lista JSON |
| `GET /api/addresses` | Una dirección propia |
| `GET /api/account/profile` | Identidad de fixture |
| `GET /api/notifications` | Contrato `items` |

Autenticación de preparación, **directa y fuera del historial ZAP**:

1. `/api/auth/me` sin cookie debe devolver 401.
2. GET `/api/auth/csrf`, conservando cookie y token en memoria.
3. POST de formulario `/api/auth/login` con CSRF; exige 204.
4. GET `/api/auth/me` con cookie; exige identidad y COMPRADOR.
5. Las lecturas protegidas pasan después por ZAP con esa cookie.

No se presenta el login como escaneado (`login_scanned: false`). El requisito
de cobertura exige contratos válidos y mensajes GET 200 correspondientes en
el historial real de ZAP, además de URLs en su árbol. No se aceptan una SPA
devuelta como HTML en lugar de API ni una exploración anónima etiquetada como
autenticada. Se rechazan URLs/métodos fuera de allowlist. `:80` se normaliza
solo para el mismo origen HTTP; otros hosts, puertos o parámetros se rechazan.
Los nodos padres sintéticos del árbol ZAP (`HistoryReference.TYPE_TEMPORARY=0`)
se registran aparte y **no** cuentan como tráfico ni cobertura. Se exigen
mensajes reales `TYPE_PROXIED=1`; tipos de spider/active scan se rechazan.
Referencia técnica: [SiteMap.createReference en ZAP 2.17.0](https://github.com/zaproxy/zaproxy/blob/v2.17.0/zap/src/main/java/org/parosproxy/paros/model/SiteMap.java).

Excluidos: pagos, webhooks, cancelaciones, reembolsos, escrituras de negocio,
registro CU-12, importaciones, administración, cambios de correo/contraseña,
cierre/revocación, proveedores externos y funcionalidades de terceros. No se
ejecuta el navegador ni se cubren chunks lazy no referidos en el HTML inicial.
Las lecturas pueden actualizar metadatos técnicos de sesión; no son pruebas
de inmutabilidad de la base ni de toda la lógica de negocio.

## Ejecución local

Requisitos: Docker Engine local con Compose v2 o superior, Python 3.10+,
conectividad para construir/descargar imágenes y memoria para los servicios
(límites: MySQL 1 GiB, backend 1,5 GiB, frontend 256 MiB, ZAP 2 GiB).
No requiere Java/Node/ZAP instalados en el host ni modificar la rama.

Desde la raíz:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/security/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/security/run.py
```

El runner imprime el proyecto y la carpeta exacta de evidencia. No iniciar el
Compose manualmente contra otra base ni combinar archivos Compose. Usa un
proyecto nuevo por ejecución. No ejecuta comandos Git; localmente la revisión
se declara `local-unverified-no-git` y se incluye un manifiesto SHA-256 de
entradas principales para trazabilidad, sin afirmar rama/commit.

Interrupción normal/SIGTERM y excepciones disparan `finally`. Si un cierre
abrupto del proceso impidió limpiar, ejecutar en el mismo workspace:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/security/cleanup.py
```

Solo considera proyectos registrados y autorizados por este runner, con
identidad validada. No realiza prune global. El respaldo comprueba el marcador
PID y omite un runner mientras siga activo. Un corte del daemon o eliminación
del workspace puede requerir recuperación manual del proyecto exacto registrado.
Las imágenes compartidas descargadas y cachés de construcción se conservan;
las imágenes locales de frontend/backend de ese proyecto se eliminan.

## Ejecución automática y artefactos

Tras la publicación autorizada en la rama indicada, abrir Actions →
**OWASP ZAP Security Scan**. El push lo dispara automáticamente. Después del
merge también corre por push a `main`. `workflow_dispatch` permite repetirlo
desde la interfaz una vez que GitHub tenga el workflow disponible; la lista
manual normalmente requiere que el archivo exista en la rama predeterminada.
El push de la rama de desarrollo no depende de esa disponibilidad manual.

No se ha publicado ni ejecutado remotamente desde esta tarea. No afirmar que
Actions aprobó hasta revisar una ejecución remota y sus artefactos.

Carpeta local: `artifacts/security-zap/rnf-zap-<id>/` (ignorada por Git).
Artefacto Actions: `security-zap-<run_id>-<run_attempt>`, retención 14 días.

| Archivo | Contenido |
|---|---|
| `zap-report.html` | Informe legible generado de alertas reales ZAP, sanitizado y con HTML escapado |
| `zap-report.json` | Alertas estructuradas, versión, cobertura, alcance, duración y evaluación |
| `zap-resumen-ejecutivo.html` | Resumen visual en español: estado técnico y de política, indicadores, hallazgos agrupados, acciones y límites |
| `execution.log` | Secuencia controlada de ZAP y lecturas, sin cabeceras/cuerpos |
| `baseline-help.txt` | Parámetros consultados del ejecutable en la imagen fijada |
| `scanner-status.json` | Estado técnico/cobertura y salida del proceso ZAP |
| `metadata.json` | Revisión CI o estado local, digest, fechas, duración y salida del runner |
| `source-sha256.json` | Huellas de entradas principales |
| `isolation.json` | Healthchecks, ausencia de puertos, redes e imágenes |
| `fixtures.json` | Migraciones y conteos comprobados |
| `cleanup.json` | Código de limpieza y recursos restantes |
| `runner.log` | Secuencia del ambiente, resultado y limpieza |

Los informes son una presentación sanitizada propia de resultados ZAP, no una
plantilla HTML nativa de ZAP. No se exportan sesión ZAP, HAR, base MySQL,
mensajes HTTP, logs privados del daemon, contraseñas, cookies ni tokens CSRF.
Los valores sensibles conocidos se reemplazan también en evidencia textual.
Ante un fallo temprano puede no haber reportes: el job debe fallar y conservar
el diagnóstico disponible, nunca fabricar un reporte satisfactorio.

### Informe ejecutivo para el profesor

Abrir primero **`zap-resumen-ejecutivo.html`** dentro del ZIP descargado. Es un
archivo autocontenido, sin JavaScript, fuentes externas ni CDN. Funciona sin
conexión, se adapta a móvil y dispone de estilos de impresión. Los dos informes
técnicos se conservan y se enlazan desde él, sin reescribir sus resultados.

El resumen utiliza directamente los datos sanitizados del mismo escaneo:

- Distingue análisis completado de aprobación por política. Código 1 por una
  alerta alta sigue siendo un bloqueo, aunque se generen los tres informes.
- Cuenta instancias y agrupa por regla, nombre y severidad. Las 48 alertas de
  referencia forman ocho tipos; no se presentan como 48 vulnerabilidades
  distintas ni como vulnerabilidades automáticamente confirmadas.
- Incluye tarjetas y barras por severidad, confianza, ubicaciones y estados
  de revisión. Mantiene visibles los estados mixtos y las excepciones.
- Explica las ocho reglas observadas en español y utiliza una explicación
  prudente para reglas futuras desconocidas, conservando el nombre original.
- Distingue investigar, confirmar aplicabilidad, corregir hallazgos confirmados,
  repetir pruebas y justificar falsos positivos. No acepta alertas por sí mismo.
- Con cero alertas lo dice expresamente, junto con las limitaciones. Si falla
  técnicamente o faltan resultados, no muestra una aprobación ni cero alertas
  como si fueran un resultado válido.

`write_reports()` lo crea junto a HTML/JSON técnicos. Al finalizar,
`run.py::finalize_executive_report()` agrega fecha en horario de Bogotá,
revisión, ID Actions y duraciones disponibles, sin modificar los resultados
técnicos. También puede producir un resumen de error si el scanner no entregó
datos. Si no se puede generar el archivo, el runner falla técnicamente.

No se necesita cambiar el workflow: la subida existente con `always()` incluye
todo `artifacts/security-zap/`, también el resumen y los resultados bloqueados.
La disponibilidad en una nueva ejecución remota debe comprobarse después de
publicar estos cambios; esta mejora se validó localmente, sin ejecutar ZAP otra
vez, usando pruebas representativas y una copia de los resultados anteriores.

Validación de esta mejora: **16 pruebas nuevas, 27 aprobadas en total**.
Se comprobaron agrupación, conteos, cero alertas, bloqueo por riesgo Alto,
reglas desconocidas, traducciones, sanitización, inyección HTML, estados
parciales, excepciones y conservación de los reportes técnicos. La copia local
de las 48 alertas produjo ocho grupos y mantuvo la salida 1. Se revisó en
Chromium a 1440 px y 390 px, sin desbordamiento móvil ni solicitudes externas;
la impresión PDF incluyó las ubicaciones y evidencias de los desplegables.
Estos controles validan la presentación, no constituyen una nueva ejecución
del scanner ni una nueva aprobación remota de GitHub Actions.

## Política e interpretación

`policy.json`: riesgo ZAP **Alto (3)** bloquea salvo excepción exacta, revisada y
vigente. Medio/Bajo/Informativo se conservan como advertencias para revisión.
No hay reglas IGNORE ni excepciones iniciales. Cada excepción necesita
`pluginId`, URL exacta, justificación, responsable de revisión y vencimiento.
Una excepción incompleta o vencida causa error, no aceptación silenciosa.

Estados: `technical_error`, `coverage_failure`, `security_findings`,
`warnings_for_review`, `no_alerts_in_scope` y alertas aceptadas temporalmente.

Los scripts empaquetados Baseline documentan 0=éxito, 1=FAIL, 2=WARN,
3=otro fallo. **No se reinterpretan como si este runner los hubiera ejecutado.**
Nuestro driver devuelve 0=reportes/cobertura válidos sin bloqueo,
1=hallazgo bloqueante, 3=fallo técnico/cobertura. El daemon se termina
deliberadamente después del reporte (por ejemplo código 143 en Linux); ese
código se conserva por separado y no equivale a un fallo del análisis.
El runner solo acepta la salida coherente con los reportes y siempre falla
si faltan informes, falla cobertura o no se puede comprobar limpieza.
No hay `continue-on-error` ni conversión general de fallos en éxito.

Para cada alerta: regla, riesgo, confianza, método/URL, parámetro, evidencia,
descripción, recomendación, referencias y estado de revisión. Confianza Alta
del scanner no equivale a vulnerabilidad confirmada por el equipo. El estado
inicial es pendiente; no se declara una vulnerabilidad confirmada sin revisión.

Ejemplos de revisión: una observación de cookie Secure sobre este objetivo
HTTP describe su transporte local; no prueba el estado de un despliegue HTTPS.
Una alerta sobre anti-CSRF puede requerir analizar el mecanismo por cabecera y
token. No marcar automáticamente estos casos como falsos positivos ni ignorar
reglas globalmente. Mantener el hallazgo y documentar la verificación concreta.
En las alertas observadas, 10096 puede identificar un número del bundle como
timestamp por heurística; hay que comprobar si representa información sensible.
90003 sobre recursos propios exige revisar el modelo de integridad de recursos.
Estas consideraciones son hipótesis de revisión, no falsos positivos resueltos.

Remediaciones se deciden después del informe: cabeceras del frontend, política
CSP compatible con Angular, transporte HTTPS, cookies y exposición de datos,
según la evidencia obtenida. Esta fase no cambia código de los CU para hacer
desaparecer alertas. Cero alertas significa únicamente cero alertas detectadas
en este tráfico y versión.

## Validación y resultados

Validación local del 13 de noviembre de 2026 (horario de Bogotá):

- Construcción real de backend Java 21 y frontend Angular/Nginx mediante sus
  Dockerfiles existentes.
- Compose válido; redes internas, cero puertos publicados, MySQL tmpfs y
  healthchecks inspeccionados antes de ZAP.
- Flyway V1–V47 comprobado contra los archivos del código; fixtures nuevas.
- Once tests de guardas, cobertura real frente a nodos sintéticos, política,
  sanitización y rechazo de evidencia truncada, aprobados.
- Sintaxis Python comprobada; workflow validado con actionlint 1.7.12,
  descargado del repositorio oficial y verificado con su checksum publicado.
- Primera ejecución válida conservada en
  `artifacts/security-zap/rnf-zap-c28b2a841e67/`: ZAP 2.17.0, 24 solicitudes
  reales, cobertura pública y COMPRADOR aprobada, 48 alertas: 1 Alta,
  3 Medias, 43 Bajas y 1 Informativa. Salida **1 por política**, no error técnico.
  Limpieza comprobada, sin contenedores/redes/volúmenes restantes del proyecto.
- Las dos ejecuciones previas fallaron al interpretar nodos padre sintéticos
  como solicitudes reales. Sus diagnósticos y pruebas de limpieza se conservan
  en carpetas separadas; no se presentan como escaneos aprobados.
- Una repetición intermedia fue interrumpida al probar la limpieza de respaldo
  concurrentemente. Se conservó como fallo técnico; se añadió una guarda de
  runner activo, comprobada por test y en vivo antes de la repetición final.
- **Ejecución final:** `artifacts/security-zap/rnf-zap-271abb763e66/`.
  Reprodujo las 48 alertas y 24 GET reales con ZAP 2.17.0; el scanner tardó
  30,035 s y el ciclo completo 91,939 s (con caché de construcción).
  Estado técnico `ok`, cobertura `passed`, salida 1 por el hallazgo Alto,
  sin excepciones. `cleanup.json` acredita cero recursos del proyecto restantes.
  Los archivos principales de implementación coinciden con el manifiesto
  SHA-256 de esa ejecución. Se comprobó que los artefactos exportados no
  contienen los valores de credenciales/identidad de la fixture.

Hallazgo prioritario real del scanner: regla **10003, Vulnerable JS Library**,
riesgo Alto y confianza Media, `GET http://frontend/main.js`, evidencia de
versión Angular **18.2.14**. Es un hallazgo **pendiente de revisión**, no una
explotación ni vulnerabilidad confirmada por el equipo. La recomendación ZAP es
actualizar la librería afectada; corresponde contrastar el detalle del advisory,
versión resuelta y aplicabilidad antes de decidir la corrección. No se modifican
dependencias ni se añade excepción para obtener un resultado verde.

Otras reglas detectadas: 10036 (versión de servidor), 10020 (anti-clickjacking),
10021 (nosniff), 10038 (CSP), 90003 (SRI), 10096 (timestamp) y 10109 (aplicación
web moderna, informativa). Las instancias por URL no son vulnerabilidades
distintas confirmadas. Revisar el HTML/JSON para evidencia y recomendaciones.

No hay ejecución GitHub Actions remota verificada ni certificación integral.
La repetición final conserva también `other`, el detalle adicional de ZAP:
la alerta 10003 enumera CVE-2026-52725, CVE-2026-50557, CVE-2026-32635,
CVE-2026-88057, CVE-2026-27970 y CVE-2026-54267, con enlaces del proveedor.
Es la información reportada por la base de detección incluida en la imagen;
su aplicabilidad y severidad individual no se certificaron en esta fase.

## Guion de sustentación (5–8 minutos)

1. **0:00–1:00:** RNF-008, disparadores y alcance; mostrar el workflow.
2. **1:00–2:00:** ejecución remota real previamente terminada, revisión y readiness.
3. **2:00–3:00:** digest/versión, modo safe y prueba de identidad/cobertura.
4. **3:00–5:00:** descargar HTML/JSON; explicar una alerta real, riesgo,
   confianza, evidencia, recomendación y estado pendiente/confirmado.
5. **5:00–6:00:** política de salida, artefactos incluso con fallos y limpieza.
6. **6:00–8:00:** límites: no Full Scan, no SCA, no todos los roles/CU,
   HTTP local y prueba funcional de permisos separada.

Dejar una ejecución automática y sus informes listos antes de presentar.
Una repetición manual en vivo muestra progreso, sin prometer que la compilación
terminará dentro de ocho minutos. Si no hay alertas, mostrarlo sin inventarlas.

## Referencias oficiales

- [ZAP Baseline: opciones y códigos](https://www.zaproxy.org/docs/docker/baseline-scan/).
- [Full Scan y sus pruebas activas](https://www.zaproxy.org/docs/docker/full-scan/).
- [Docker ZAP y exposición de su API](https://www.zaproxy.org/docs/docker/about/).
- [Automatización de ZAP](https://www.zaproxy.org/docs/automate/automation-framework/).
- [Regla pasiva 10003](https://www.zaproxy.org/docs/alerts/10003/).
- [GitHub: workflow_dispatch y rama predeterminada](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch).
