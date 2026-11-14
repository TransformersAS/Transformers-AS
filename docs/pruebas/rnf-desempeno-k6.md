# ASR-005: evidencia reproducible de desempeño con k6

## Objetivo y alcance

Evaluar el rendimiento bajo la carga esperada con aplicación Spring Boot, MySQL y frontend Nginx reales, en un ambiente local exclusivo. Esta evidencia corresponde al estado de trabajo de la implementación; no se ejecutó ninguna operación Git para identificar o cambiar la rama. La revisión queda como `local-no-verificada-sin-git`: los manifiestos SHA-256 y los identificadores de imágenes permiten identificar los archivos y binarios utilizados sin atribuir un commit no verificado.

Los resultados históricos de `mediciones-asr-005-asr-006.md` se conservan y no se mezclan con esta medición. Tampoco se vuelve a afirmar su cobertura de checkout sobre los contratos actuales.

## Arquitectura y seguridad del ambiente

`python3 scripts/performance/run.py` crea un proyecto `rnf-perf-<UUID>` nuevo. k6 accede a Nginx (`http://frontend`), que enruta al backend; el backend usa exclusivamente MySQL del proyecto. Las redes `target` y `database` son internas, sin puertos publicados ni salida a proveedores. MySQL almacena todo en tmpfs; no se reutilizan volúmenes. Las únicas carpetas montadas son scripts de prueba, imágenes sintéticas y resultados de esta ejecución. La verificación estática y la inspección de contenedores/redes comprueban estas condiciones antes de la carga.

Se usa un contexto Docker local Unix. El runner descarta el entorno heredado salvo PATH/HOME/TMPDIR/DOCKER_CONFIG, ignora `.env` y establece configuración explícita: datos demo desactivados, pagos y recuperación desactivados, tokens de Mercado Pago e IA vacíos, correo/push/colas desactivados y logística simulada sin polling. No se ejecutan checkout, CU-12 ni registros de vendedor. Las credenciales incluidas son públicas, exclusivas del fixture y no deben usarse en otro ambiente.

| Componente | Versión/configuración | Límite |
|---|---|---|
| Backend | Java 21; Spring Boot 4.1.1 según POM; imagen construida con Dockerfile existente | 2 CPU, 1 GiB; heap 128–512 MiB; pool configurado 20 |
| MySQL | 8.4.11; buffer InnoDB 128 MiB; máximo 100 conexiones | 1 CPU, 1 GiB, incluye datos en tmpfs |
| Frontend | Angular 18.2.14/Ionic 8.8.19 existentes; servido por Nginx | 0,25 CPU, 128 MiB |
| Generador | k6 0.57.0, imagen fijada por digest en Compose | 1,5 CPU, 512 MiB |

Docker informó 10 CPU y 8.319.504.384 bytes de memoria en una VM Linux aarch64; Engine 29.8.0. Comparte el computador con otros servicios. Estos servicios solo se observaron con `docker stats`; no se modificaron. El control previo exige al menos 3.000 MiB estimados disponibles; durante cada corrida detiene el proceso si quedan menos de 700 MiB o si la suma de CPU supera el 70 % de los núcleos de Docker. La estimación suma memoria reportada por contenedor, no mide presión física del host ni sustituye un laboratorio dedicado.

Al terminar, incluso ante errores, se elimina exclusivamente el proyecto generado con `down --volumes --remove-orphans --rmi local`; se verifica que no queden sus contenedores, redes o volúmenes. Se conservan los reportes. Las capas de caché y las imágenes descargadas pueden permanecer en Docker.

## Preparación de datos

Se exige una base nueva, cero cuentas y cero productos después de Flyway. Se compara la lista de migraciones aplicadas con los archivos vigentes: en la ejecución documentada se verificaron V1–V47. Solo entonces se inserta el fixture:

- 1.000 productos activos y visibles, stock 1.000 por producto, precios deterministas.
- 100 compradores `perf001@example.test` a `perf100@example.test`, rol COMPRADOR, carrito, perfil y dirección propios.
- Tres fichas: producto 1 con 1 imagen/1 variante/1 reseña; producto 2 con 3/3/5; producto 3 con 5/5/20.
- 26 reseñas y pedidos entregados sintéticos que satisfacen las relaciones de la base. No se realizan compras ni pagos para generarlos.
- PNG deterministas de 32, 128 y 256 píxeles servidos localmente. Sus bytes son fixtures, no fotografías representativas de producción. Las entradas de imágenes de cada ficha reutilizan el PNG correspondiente a su tamaño; k6 descarga cada entrada secuencialmente, sin modelar la caché ni el paralelismo del navegador.

Antes de cada corrida se vacían favoritos e ítems, se eliminan direcciones adicionales, se restablecen preferencias y sesiones; se comprueban de nuevo 1.000 productos, 100 cuentas, 100 direcciones y 26 reseñas. El preflight HTTP exige exactamente 1.000 productos visibles. Esto mantiene la comparación sobre el mismo volumen inicial.

## Escenarios y metodología

Primero se ejecutan dos validaciones pequeñas: 2 VU, 2 segundos de calentamiento y 10 de medición, para catálogo y recursos. Solo si ambas pasan se ejecuta catálogo y recursos con 50 VU, seguidos de los mismos escenarios con 100 VU. Cada corrida principal usa 10 segundos de calentamiento y 60 de medición, ejecutor `constant-vus` y pausa de 250 ms entre recorridos. VU representa un ejecutor concurrente, no una solicitud simultánea ni una persona real.

En recursos se autentican secuencialmente las cuentas antes del escenario. Se comprueban correo y rol; cada VU recibe exclusivamente sus cookies y CSRF, y modifica su carrito, favoritos, perfil y direcciones. El producto usado también es propio de cada VU. El login no forma parte del P95 de operaciones ni del calentamiento. Su costo y el arranque sí se incluyen en la duración total del proceso.

| Escenario | Operaciones medidas | Umbral |
|---|---|---|
| Catálogo | GET listado, validación de los 1.000 productos | RNF-016: P95 ≤ 3.000 ms, cobertura HTTP parcial |
| Ficha | GET detalle/variantes, reseñas, resumen de calificación y descarga de todas las imágenes; alterna tres perfiles | RNF-017: P95 ≤ 3.000 ms por operación y por ficha HTTP completa |
| Carrito | POST agregar, GET leer, PATCH cantidad, DELETE quitar | RNF-018: P95 ≤ 2.000 ms por operación |
| Favoritos | PUT guardar, GET leer, DELETE quitar | RNF-018: P95 ≤ 2.000 ms por operación |
| Perfil | GET perfil real; PUT alternar preferencias con control de versión | RNF-018: P95 ≤ 2.000 ms por operación |
| Direcciones | POST crear y GET listar verificando propietario | RNF-018: P95 ≤ 2.000 ms por operación |
| Recorridos completos | Listado + ficha completa; o las once operaciones de recursos en secuencia | RNF-024: P95 ≤ 4.000 ms y errores < 2 %; comparar 50/100 |

El recorrido de recursos agrupa varias funcionalidades y es una condición adicional conservadora: su P95 no es la latencia de una operación aislada. Las latencias individuales permanecen separadas. El tiempo del recorrido y de la ficha completa usa reloj de pared e incluye validación JS y transferencias secuenciales. Las peticiones usan `timings.duration` de k6. No se cronometra decodificación/renderizado del navegador.

Solo se registran métricas personalizadas de iteraciones iniciadas tras el calentamiento. Una iteración puede terminar después del minuto de medición, dentro de `gracefulStop=20s`; por ello se conserva la duración observada y se usa esta para solicitudes/s. El resumen HTTP general de k6 incluye preparación y calentamiento: no es el indicador oficial del reporte. `active_vus` conserva los usuarios activos; `measured_vus` exige que participen todos los previstos.

Cada operación conserva intentos, éxitos, errores, media, P95 y máximo; también P95 de respuestas exitosas. Se exige al menos 20 éxitos por operación en carga principal (1 en smoke). Se evalúan ambos P95 y errores estrictamente menores al 2 %. Una respuesta HTTP 200 con cuerpo incorrecto invalida la evidencia; nunca cuenta como éxito. La falta de muestras, usuarios o recorridos terminados produce **No evaluado**. Un incumplimiento válido de latencia produce **No cumple**. Los umbrales oficiales no se alteran por parámetros de ejecución.

## Inventario reutilizado

| Archivo previo | Decisión |
|---|---|
| `scripts/k6/lib/sesion.js` | Reutilizado sin cambios: cuenta por VU, CookieJar, login y CSRF. El nuevo escenario exige pool suficiente y verifica identidad/rol. |
| `catalog-browse.js` | Conservado. No certifica filtros Angular ni todas las partes de ficha; el escenario nuevo agrega contratos y métricas separadas. |
| `buyer-resources.js` | Conservado. El nuevo escenario mide `/api/account/profile`, no usa `/api/auth/me` como sustituto de perfil. |
| `checkout-flow.js` | Conservado y no ejecutado: ruta de procesamiento legado devuelve 410 por defecto. |
| `seller-register.js` | Conservado y no ejecutado; fuera de alcance CU-12. |
| `performance-demo-seed.sh/.py` | Conservados. El fixture nuevo verifica el esquema actual en vez de depender del seed histórico. |
| `scripts/quality/tests/test_k6_contract.py` | Conservado. Se agregan pruebas específicas del nuevo escenario y evaluador. |
| `monitoring/prometheus.yml` | Conservado: su descubrimiento Swarm no se conecta a este Compose efímero. |
| Micrometer/Actuator | Readiness real usada para arranque. CPU/memoria se registran con Docker; no se afirma haber medido saturación Hikari, GC o consultas mediante Prometheus. |

## Resultados reales de la ejecución final

Ejecución del **13 de noviembre de 2026**, 22:30:07–22:37:29 America/Bogota (03:30:07–03:37:29 UTC del 14): **442,21 s** de extremo a extremo, incluyendo preparación, autenticación, smoke, carga y limpieza. Proyecto `rnf-perf-c5edc1e67b59`. k6 `v0.57.0 (50afd82c18, go1.23.6, linux/arm64)`; Docker Compose 5.5.1. Las dos corridas smoke y las cuatro principales terminaron con exit 0.

| Recorrido | VU previstos/medidos | Media ms | P95 ms | Errores | HTTP/s | Ventana observada s | Estado |
|---|---|---|---|---|---|---|---|
| Catálogo + ficha | 50/50 | 199.53 | 414.00 | 0.00 % | 774.89 | 60.219 | Cumple |
| Recursos autenticados | 50/50 | 861.94 | 1137.00 | 0.00 % | 490.97 | 60.425 | Cumple |
| Catálogo + ficha | 100/100 | 599.47 | 975.00 | 0.00 % | 817.04 | 60.347 | Cumple |
| Recursos autenticados | 100/100 | 2031.00 | 2663.00 | 0.00 % | 468.39 | 61.225 | Cumple |

Al duplicar la carga, el P95 del recorrido de catálogo pasó de 414 a 975 ms (2,36 veces), con HTTP/s de 774,89 a 817,04 (+5,44 %). En recursos pasó de 1.137 a 2.663 ms (2,34 veces), mientras HTTP/s bajó de 490,97 a 468,39 (−4,60 %). El throughput de recursos no creció al duplicar VU y la latencia aumentó: no se extrapola capacidad lineal ni se atribuye la causa sin instrumentación adicional.

| Indicador por RNF | 50 VU, P95 ms | 100 VU, P95 ms | Evaluación dentro del alcance |
|---|---|---|---|
| RNF-016: listado HTTP | 130,82 | 292,82 | Cumple listado; filtros/orden/búsqueda Angular pendientes |
| RNF-017: ficha completa pequeña | 254,00 | 679,00 | Cumple HTTP |
| RNF-017: ficha completa mediana | 290,90 | 725,00 | Cumple HTTP |
| RNF-017: ficha completa grande | 370,90 | 887,80 | Cumple HTTP |
| RNF-018: mayor P95 entre las once operaciones | 188,20 | 423,27 | Cumplen las once operaciones implementadas |
| RNF-024: ambos recorridos | 414 / 1.137 | 975 / 2.663 | Cumplen P95 ≤ 4.000 ms y error < 2 % |

Todos los contratos medidos fueron válidos, todas las iteraciones medidas iniciadas terminaron y participaron todos los VU previstos. La menor cantidad de éxitos por operación en cada corrida fue 2.219 (catálogo 50), 2.345 (catálogo 100), 2.697 (recursos 50) y 2.607 (recursos 100), por encima del mínimo 20. Los JSON preservan el detalle por operación y los P95 de respuestas exitosas.

Durante las corridas principales se conservaron 53 muestras de recursos: mínimo estimado disponible 2184 MiB; máximo agregado de CPU 623.26 % de un núcleo (sobre 10 núcleos). No se activó el freno de recursos. Estas muestras incluyen preparación del generador y no permiten aislar por sí solas el cuello de botella.

La limpieza obtuvo exit 0; la inspección final confirmó cero contenedores, redes y volúmenes con la etiqueta del proyecto. Los intentos previos se conservaron.

Evidencia final: [`performance-executive.html`](../../artifacts/performance/rnf-perf-c5edc1e67b59/performance-executive.html), [`performance-summary.json`](../../artifacts/performance/rnf-perf-c5edc1e67b59/performance-summary.json), resultados originales en `results/` y metadatos en la misma carpeta. El HTML se regeneró tras la carga para mejorar las etiquetas y la comparación; los JSON originales permanecen intactos. `source-sha256.json` conserva las fuentes iniciales del instrumento; `source-sha256-final.json` agrega una captura posterior de fuentes finales y aplicación. Los IDs de las imágenes efectivamente ejecutadas están en `isolation.json`.

## Cobertura y pendientes

| RNF | Alcance acreditable por esta suite | Brecha |
|---|---|---|
| 016 | Listado HTTP de 1.000 productos a 50/100 VU | Búsqueda, filtros y ordenamiento están en Angular (`catalogo-filtros.ts`); requieren medición de navegador. RNF completo pendiente. |
| 017 | Ficha HTTP completa con variantes, imágenes y reseñas a 50/100 VU | No acredita visualización/renderizado ni imágenes reales de producción. |
| 018 | CRUD de carrito, guardar/leer/quitar favoritos, leer/actualizar preferencias y crear/leer direcciones | API actual no ofrece edición/eliminación de direcciones. No se cambia correo/contraseña/datos personales ni se generan notificaciones. |
| 024 | Comparación de los dos recorridos definidos | Una corrida por carga de 60 s no demuestra estabilidad prolongada, distribución multinodo ni todos los CU. |
| 019 | No evaluado | Verificar checkout vigente, separar tiempo interno del proveedor y preparar prueba segura específica. No ejecutar ruta legado 410. |
| 020 | No evaluado | Requiere medición extremo a extremo hasta visibilidad de mensajes/notificaciones en cliente, no solo aceptación HTTP. |
| 021 | No evaluado | Requiere fixture Excel válido de hasta 500 filas, ejecución y verificación del procesamiento completo ≤ 60 s. |
| 022 | No evaluado | Este fixture tiene 26 pedidos, no 10.000; falta escenario de analítica y validación de resultados ≤ 5 s. |
| 023 | No evaluado | IA desactivada. Requiere escenario autorizado, proveedor/control de cuotas y medición de disponibilidad P95 ≤ 5 s. |

## Repetición y reportes

Durante la preparación, un límite inicial de 640 MiB para MySQL resultó insuficiente: el proceso terminó por memoria (exit 137), antes de ejecutar carga alta. Se aumentó a 1 GiB, considerando que tmpfs también consume memoria del contenedor. Las carpetas de intentos fallidos se conservan con sus diagnósticos y estado No evaluado. Posteriormente pasaron ambas pruebas pequeñas contra MySQL/backend reales. Esta corrección del ambiente no modifica los umbrales de desempeño.

Las nueve pruebas de `scripts/performance/tests/test_performance.py` pasaron: verifican rechazo de muestras insuficientes, contratos incorrectos, iteraciones incompletas, participación incompleta, límites P95 y tasa estricta de errores; también fixture, control de recursos y HTML. Dos de ellas ejecutan k6 local contra un servidor HTTP simulado, una con respuesta válida y otra con HTTP 200 pero cuerpo incorrecto. Son pruebas del instrumento, separadas de la evidencia de carga contra la aplicación real.

Desde la raíz del repositorio, con Python 3, Docker local y Compose disponibles:

```bash
python3 -m unittest discover -s scripts/performance/tests -v
python3 scripts/performance/run.py --smoke-only
python3 scripts/performance/run.py
# Opcional: ventana mayor, mismos umbrales y ambos niveles de VU
python3 scripts/performance/run.py --duration 180 --warmup 20
```

k6 local es opcional para los dos tests de contrato con servidor simulado; si no está disponible esos tests se omiten explícitamente. La carga contra aplicación real siempre usa la imagen Docker fijada. No ejecutar manualmente la suite contra un stack existente. El runner no admite una URL externa ni variables de conexión de la base compartida.

Cada ejecución conserva `artifacts/performance/rnf-perf-<UUID>/`: `metadata.json`, `fixtures.json`, `isolation.json`, `capacity-before.json`, `source-sha256.json`, resultados originales k6 en `results/`, evaluaciones y muestras de recursos por corrida, logs saneados, `performance-summary.json` y `performance-executive.html`. El HTML es autocontenido y puede abrirse sin servidor. `artifacts/` está ignorado: copiar/exportar la carpeta de evidencia para la sustentación; no asumir que viajará con el próximo commit.

El runner sale 0 si todos los escenarios ejecutados cumplen; 1 ante incumplimiento medido y 3 si no pudo completar la validación o limpieza. Un smoke aprobado solo acredita contratos y preparación, no los RNF a 100 VU.

## Guion de sustentación (7 minutos)

1. **0:00–0:45.** Presentar ASR-005 y límites oficiales: catálogo/ficha 3 s, operaciones frecuentes 2 s, recorridos 4 s y errores < 2 %. Aclarar el alcance HTTP.
2. **0:45–1:45.** Mostrar Compose: MySQL real, backend real, redes internas, sin puertos, base efímera y proveedores desactivados. Mostrar `isolation.json` y limpieza.
3. **1:45–2:30.** Abrir `fixtures.json`: V1–V47, 1.000 productos, 100 compradores y perfiles de ficha 1/3/5 imágenes con 1/5/20 reseñas.
4. **2:30–3:30.** Explicar preparación, cookies/CSRF por VU, calentamiento y medición. Mostrar un resultado original y la condición de muestras mínimas; no usar solo porcentaje de errores.
5. **3:30–5:15.** Abrir el HTML, comparar 50 y 100 VU: P95, media, errores y HTTP/s. Expandir operaciones para distinguir la ficha completa y las once operaciones frecuentes del recorrido agregado. Mostrar cualquier incumplimiento sin cambiar umbrales.
6. **5:15–6:15.** Explicar cobertura parcial de filtros Angular, preferencias de perfil y direcciones; RNF-019–023 pendientes. Aclarar que un minuto no es estabilidad prolongada ni capacidad de producción.
7. **6:15–7:00.** Mostrar comandos de repetición y ruta de evidencias. Usar la evidencia ya obtenida durante la presentación; una ejecución completa incluye compilación y autenticación fuera de medición y puede exceder el tiempo de exposición.

## Archivos exactos para el próximo commit

Solo estos archivos nuevos de implementación/documentación; no incluir artefactos, `__pycache__` ni cambiar archivos ajenos:

- `compose.performance.yaml`
- `scripts/k6/performance.js`
- `scripts/performance/seed.py`
- `scripts/performance/report.py`
- `scripts/performance/run.py`
- `scripts/performance/tests/test_performance.py`
- `docs/pruebas/rnf-desempeno-k6.md`

No se ejecutaron commit, push, merge, cambios de rama ni otras operaciones Git.
