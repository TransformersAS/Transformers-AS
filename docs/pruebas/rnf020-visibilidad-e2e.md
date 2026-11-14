# RNF-020: latencia hasta visibilidad de mensajes y notificaciones

## Requisito y criterio

Para cada dirección y canal, al menos el 95 % de los eventos esperados debe ser visible en 5.000 ms o menos. Se evalúan cuatro grupos independientes: comprador → vendedor/mensajes, comprador → vendedor/notificaciones, vendedor → comprador/mensajes y vendedor → comprador/notificaciones. No se promedian canales para declarar cumplimiento.

Esta suite complementa `rnf-desempeno-k6.md`. No modifica sus scripts, umbrales, fixtures, Compose ni evidencia histórica. No ejecuta Git ni atribuye un commit o una rama que no se hayan verificado. Los manifiestos SHA-256 y los IDs de imágenes identifican fuentes y binarios de prueba.

## Arquitectura y aislamiento

`python3 scripts/rnf020/run.py` combina `compose.performance.yaml` y el override `compose.rnf020.yaml`. Crea un proyecto exclusivo `rnf020-<UUID>` con MySQL real sobre tmpfs, backend Spring Boot real, Angular/Ionic compilado y servido por Nginx y Chromium real en el contenedor Playwright. El navegador pertenece únicamente a la red interna `target`; solo el backend alcanza la red interna de base de datos. No se publican puertos ni se reutilizan redes, bases, volúmenes o contenedores ajenos.

Se reutilizan helpers del runner de desempeño mediante importación, sin ejecutar su `main` ni modificarlo. Se descarta el entorno heredado salvo variables necesarias para Docker local, se ignora `.env` y se verifican configuración, montajes, límites, contexto Unix local, readiness y pertenencia de las redes. Proveedores de pago, correo, IA, push, polling logístico y colas externas están desactivados. Las notificaciones internas sí se persisten mediante el flujo de negocio real; logística y transporte externo se mantienen simulados.

Límites: backend 1,5 CPU/1 GiB, heap 128–512 MiB, pool 20; MySQL 1 CPU/1 GiB con datos tmpfs y buffer InnoDB 128 MiB; Nginx 0,25 CPU/128 MiB; navegador 1,5 CPU/1 GiB, shared memory 256 MiB. La imagen base Playwright 1.63.0 está fijada por digest, y el paquete Node por lockfile. El override añade un servidor HTTPS interno al Nginx existente, con certificado autofirmado generado para `frontend`. Los contextos del navegador ignoran errores de certificado dentro de la red interna aislada (`ignoreHTTPSErrors`) y se verifica `isSecureContext` y `crypto.randomUUID()` antes de enviar. La clave privada vive en un directorio temporal fuera de los artefactos y se elimina al limpiar. No se publica el puerto ni se modifica el frontend. El certificado y su hash no acreditan confianza de una CA pública; se mide el recorrido con TLS local. Angular no se actualiza. Se registran CPU/memoria de Docker durante la ejecución. Se exige al menos 3.000 MiB disponibles estimados al iniciar y se detiene ante menos de 700 MiB o CPU agregada superior al 70 % de los núcleos disponibles.

El bloque final elimina exclusivamente el proyecto generado y verifica que no queden sus contenedores, redes ni volúmenes. Una limpieza no verificable impide aprobar la ejecución. Las imágenes descargadas y cachés de construcción pueden permanecer. Se conservan todos los artefactos históricos.

## Datos y sesiones

Después de Flyway se exige una base vacía en cuentas y productos y se comparan las versiones aplicadas con las migraciones vigentes. El fixture crea un comprador (ID 1), un vendedor (ID 2), un segundo comprador ajeno (ID 3), una tienda propiedad del vendedor y un producto activo. Todos los datos y credenciales son ficticios. No se insertan mensajes ni notificaciones para simular tiempos.

Se autentican tres BrowserContexts aislados mediante el contrato real CSRF/login. Se verifican ID, correo y rol activo. El comprador inicia una conversación real por API durante la preparación, sin incluir ese tiempo en la medición. El tercer comprador debe recibir 404 al consultar conversación/historial ajenos, 403 en bandeja de vendedor y una bandeja propia vacía.

El navegador mantiene cuatro páginas de la aplicación: conversación del comprador, conversación del vendedor, bandeja del comprador y bandeja del vendedor. Se abren mediante botones reales antes de medir. No se inyecta estado Angular ni se utilizan rutas Playwright interceptadas o respuestas mock. Chromium corre sin interfaz gráfica con viewport de 1.440 × 1.080, zona horaria UTC, idioma es-CO y service workers bloqueados; no se activa push ni se modela caché offline. Cada observación comprueba que el documento figure visible.

## Metodología E2E y reloj

Primero se ejecutan cinco eventos por dirección como smoke. Se verifican DOM, persistencia, correlación, sesiones y ausencia de duplicados. Si falla, se bloquea la medición principal. Después se ejecutan por defecto 100 eventos por dirección: en cada ronda el comprador envía y se observa en paralelo su recepción y notificación; después el vendedor responde y se observa en paralelo la recepción y notificación del comprador. Son turnos alternos, no dos envíos simultáneos. Solo existe un evento en vuelo por destinatario. Los eventos smoke permanecen separados de las muestras principales. Entre rondas se espera entre 50 y 499 ms, calculados de forma determinista por ronda; esa espera queda fuera del `t0` del siguiente mensaje.

Cada mensaje contiene un identificador único `RNF020-<etapa>-<dirección>-<ronda>-<UUID>`. El texto se prepara antes de medir. `t0` se obtiene de `node:perf_hooks.performance.now()` inmediatamente antes de iniciar el clic real en Enviar mensaje. La detección de ambas recepciones usa el mismo reloj del proceso Node que controla todos los contextos; no se restan relojes de navegadores o del servidor.

`message_latency_ms` y `notification_latency_ms` son diferencias respecto a `t0`. Incluyen acción del emisor, transferencia, procesamiento/persistencia, espera de polling y renderizado. Son mediciones conservadoras; la confirmación HTTP nunca sustituye la observación del destinatario.

La observación consulta únicamente el DOM de la página, aproximadamente cada 50 ms, sin acelerar las peticiones de la aplicación. Comprueba `document.visibilityState`, existencia del panel, dimensiones visibles e intersección del elemento con su panel. Antes de cada ronda se prepara el scroll: conversación al final y bandeja al inicio. Después de `t0` no se desplaza al destinatario, no se navega, recarga, refresca ni consulta una API para declarar visibilidad. Los intervalos reales de la aplicación permanecen en 3.000 ms para conversaciones y 2.000 ms para notificaciones.

Una página sin panel o con pestaña oculta produce un fallo verificable del instrumento; no se convierte en éxito. El plazo de observación es 12 s: un evento visto después de 5 s conserva su latencia y cuenta como tardío; uno no visto no obtiene una latencia inventada. El límite de 12 s no cambia el RNF de 5 s.

## Correlación de las notificaciones

La bandeja presenta textos genéricos y no expone el ID de la notificación en el DOM. Por ello se exige simultáneamente:

1. Solo un evento pendiente por destinatario y una línea base del número de filas y de IDs de la última respuesta de polling.
2. Exactamente una fila adicional, situada al principio, con título, texto, fecha renderizada y botón Abrir conversación correspondientes a la nueva notificación.
3. Una respuesta **pasiva del polling natural de la página**, con exactamente un ID nuevo, en la primera posición y con `conversationId` correcto. No se realizan peticiones extra para encontrar visibilidad.
4. Respaldo posterior en MySQL: mensaje con texto único, emisor y conversación correctos; exactamente una notificación con `event_key=conversation-message-<id>`, ID observado y destinatario esperado (STORE/tienda 1 o BUYER/comprador 1).

El reloj se detiene al comprobar el DOM con ese respaldo pasivo; la consulta MySQL posterior solo valida la asociación. Si falta una parte, el evento es no verificable y no cuenta como éxito del canal. La persistencia del mensaje se comprueba por separado para que una notificación no correlacionada no borre evidencia válida del mensaje.

## Métricas y estados

El denominador de cada grupo es la cantidad **esperada**, incluyendo eventos fallidos, no intentados y timeouts. Se conservan intentos, éxitos verificados, fallos, no verificables, eventos visibles en ≤ 5 s y su porcentaje, media, P50, P95, máximo y tasa de errores funcionales E2E por canal (incluye envío inválido, falta de persistencia/visibilidad/correlación y eventos no ejecutados; un evento tardío pero válido se informa en el porcentaje de 5 s). Percentiles por rango más cercano (`ceil(p*n)-1`) sobre latencias verificadas observadas. Los fallos sin aparición no entran como latencias cero: siguen incluidos en el porcentaje global.

Una corrida principal completa requiere al menos 100 eventos por dirección. Cero datos, smoke, ejecución parcial o limpieza no verificada dan **No evaluado**. Una corrida completa con menos del 95 % dentro de 5 s en cualquier grupo da **No cumple**. Solo se declara **Cumple** para el escenario medido si los cuatro grupos alcanzan su propio criterio. El estado global del RNF permanece **No evaluado** si el escenario alternado cumple pero existe el fallo simultáneo documentado; el JSON separa `scenario_state` y `state`. No se presenta un resultado favorable parcial como certificación general del requisito. El reporte no usa estados verdes para muestras insuficientes.

## Validación del instrumento

Las pruebas Node con `node:test` verifican cálculo de percentiles/porcentajes, inclusión de fallos en el denominador, frontera 95 %/5.000 ms, separación de grupos, rechazo de cero/parciales/smoke, correlación y destinatario, independencia de persistencia y notificación, privacidad/escape del reporte y observación de DOM en Chromium. Los tests de DOM usan HTML sintético para probar el instrumento, no para acreditar latencia del marketplace. No usan `page.route`.

Las pruebas Python verifican que la limpieza solo apunte al proyecto generado, que recursos restantes o fallos de limpieza no se aprueben y que el fixture use identidades distintas sin insertar mensajes/notificaciones. Además, cada ejecución real verifica aislamiento de la tercera cuenta y ausencia efectiva de recursos del proyecto al terminar.

## Reproducción

Requisitos: Docker local con Compose, Python 3, Node.js y acceso a las imágenes/dependencias durante la construcción. No se necesita Chromium instalado en el computador.

```bash
python3 -m unittest discover -s scripts/rnf020/tests -p 'test_*.py' -v
python3 scripts/rnf020/run.py --smoke-only
python3 scripts/rnf020/run.py
# Mayor cantidad, mismo umbral oficial:
python3 scripts/rnf020/run.py --events 150
# Reproducir el hallazgo de envío simultáneo (el smoke bloquea la carga si falla):
python3 scripts/rnf020/run.py --simultaneous --smoke-only
```

El runner ejecuta también las pruebas Node dentro de la misma imagen Chromium antes del smoke. No se admite una URL de destino externa, credenciales personales ni una base preexistente. No ejecutar `measure.cjs` contra otros stacks.

En `artifacts/performance/rnf020-<UUID>/` quedan:

- `rnf020-summary.json` y `rnf020-executive.html` autocontenido, sin recursos externos.
- `metadata.json`, `source-sha256.json`, `isolation.json`, capacidad inicial y muestras de CPU/memoria por etapa.
- `results/smoke-raw.json` y `results/measurement-raw.json`, con observaciones y tiempos por evento.
- `backing.json`, con asociación sintética de mensajes/notificaciones persistidos.
- Logs del instrumento y etapas; capturas de fallos cuando corresponda. No se guardan cookies, storageState, contraseñas ni trazas de red con cabeceras de sesión. Las capturas contienen únicamente cuentas y mensajes sintéticos.

Los resultados originales se preservan; el resumen agrega la validación de persistencia/correlación. `artifacts/` está ignorado por Git: exportar la carpeta para la sustentación. Exit 0 indica éxito del modo ejecutado (smoke no acredita el RNF); exit 1, incumplimiento medido; exit 3, validación integral pendiente, evidencia incompleta o problemas de infraestructura/limpieza. Con el hallazgo simultáneo vigente, un escenario alternado aprobado mantiene exit 3 y RNF general No evaluado, aunque los cuatro grupos medidos cumplan.

## Resultados reales de la ejecución final

Proyecto **`rnf020-97ce5291d927`**, ejecutado el **13 de noviembre de 2026** en America/Bogota (2026-11-14T04:48:23.761155+00:00 a 2026-11-14T04:56:54.960573+00:00 UTC). Duración total **511.20 s**; proceso principal de navegador **369.99 s**, incluye preparación de sus páginas y sesiones; smoke **53.33 s**. El tiempo individual por evento excluye esa preparación.

Hardware registrado: Docker Linux/aarch64, 10 CPU lógicas y 8.319.504.384 bytes (7,75 GiB) de memoria; Engine 29.8.0, Compose 5.5.1; Chromium 153.0.8010.12 y Playwright 1.63.0. Backend Java 21.0.13/Spring Boot 4.1.1 y MySQL 8.4.11, con Flyway V1–V47 verificado. Un computador compartido con otros contenedores; no se identifica su capacidad como producción.

**Escenario principal de turnos alternos: Cumple. Estado general de RNF-020: No evaluado**, por el fallo simultáneo documentado que queda pendiente de corrección y validación. El proceso de navegador terminó con exit 0 y el runner con exit 3 intencional: no certifica el requisito integral aunque los grupos principales cumplan. No fue una interrupción de infraestructura en esta ejecución.

| Dirección | Canal | Esperados / verificados | ≤ 5 s | Media ms | P50 ms | P95 ms | Máximo ms | Fallos / no verificables | Estado del grupo |
|---|---|---|---|---|---|---|---|---|---|
| Comprador → vendedor | Mensajes | 100 / 100 | 100.00 % | 923.03 | 579.31 | 3074.46 | 3918.55 | 0 / 0 | Cumple |
| Comprador → vendedor | Notificaciones | 100 / 100 | 100.00 % | 1161.37 | 1320.78 | 2102.42 | 3918.50 | 0 / 0 | Cumple |
| Vendedor → comprador | Mensajes | 100 / 100 | 100.00 % | 1807.40 | 1968.53 | 3122.46 | 4318.07 | 0 / 0 | Cumple |
| Vendedor → comprador | Notificaciones | 100 / 100 | 100.00 % | 1353.02 | 1176.00 | 2166.70 | 4312.31 | 0 / 0 | Cumple |

**200 mensajes reales y 200 notificaciones reales** evaluados en la etapa principal: 100 mensajes y sus notificaciones por dirección, equivalentes a 400 observaciones de latencia. Los diez mensajes y diez notificaciones smoke se contabilizan aparte. MySQL confirmó **210 mensajes y 210 notificaciones** en total, todos con identificadores verificables; no se insertaron artificialmente en las tablas. Los cuatro grupos principales tuvieron tasa de errores funcionales de 0 %, sin duplicados o muestras no verificables.

Se conservaron 61 muestras de recursos: mínimo disponible estimado **1677 MiB** y máximo agregado **520.83 % de un núcleo** (52,083 % de diez núcleos). No se activó el freno. La limpieza terminó con exit 0 y cero contenedores, redes y volúmenes del proyecto; también se verificó la eliminación de la clave TLS temporal.

Las **10 pruebas Node/Chromium y 3 pruebas Python pasaron**. La prueba adicional del evaluador comprueba que cuatro grupos alternados aprobados no conviertan en verde el requisito general si existe un fallo simultáneo conocido. Se compararon los hashes de todas las fuentes registradas después de ejecutar: no cambiaron respecto al manifiesto inicial. Los archivos existentes de aplicación y desempeño permanecieron intactos.

Informes finales: [`rnf020-executive.html`](../../artifacts/performance/rnf020-97ce5291d927/rnf020-executive.html) y [`rnf020-summary.json`](../../artifacts/performance/rnf020-97ce5291d927/rnf020-summary.json). En esa misma carpeta se copiaron, sin eliminar los originales, `known-simultaneous-summary.json`, `known-simultaneous-backing.json` y [`known-simultaneous-seller.png`](../../artifacts/performance/rnf020-97ce5291d927/known-simultaneous-seller.png). Esa evidencia corresponde al smoke simultáneo anterior y se mantiene separada de los 100 eventos por dirección. Exportar esta carpeta completa para conservar datos, fallo conocido e informe juntos.

## Límites de la evidencia

Esta medición usa dos participantes y una conversación, texto y bandeja interna en un computador compartido con otros contenedores. No reemplaza los escenarios k6 de 50/100 VU ni acredita estabilidad prolongada o producción. El polling depende de visibilidad y planificación del navegador. El muestreo del DOM agrega sobrecarga y resolución de unos 50 ms más planificación. La correlación depende del contrato actual de orden descendente y combinación de la bandeja: cambios de presentación exigen adaptar/verificar el instrumento, no asumir correlación.

**Push del navegador: no evaluado.** Ver una fila en la bandeja no demuestra entrega del sistema operativo, permisos push ni proveedor externo. También quedan fuera correo, adjuntos, grupos, varias tiendas, redes móviles y recepción en pestañas ocultas.

## Guion breve de sustentación (5 minutos)

1. **0:00–0:45:** explicar RNF-020: cuatro grupos independientes, 95 % de todos los eventos en ≤ 5 s; HTTP 200 no prueba visibilidad.
2. **0:45–1:30:** mostrar Compose, cuentas aisladas, MySQL/Flyway reales, cuatro páginas de destinatarios y polling original.
3. **1:30–2:30:** mostrar una muestra JSON: texto único, `t0`, dos latencias DOM y respaldo `message_id/event_key/notification_id`.
4. **2:30–3:45:** abrir el HTML y comparar los cuatro grupos, porcentajes y percentiles. Mostrar fallos/no verificables dentro del denominador y cualquier incumplimiento.
5. **3:45–5:00:** aclarar bandeja frente a push, cantidad real de muestras, límites y comandos de repetición; mostrar limpieza verificada y ubicación de evidencia.

## Incidencias de preparación conservadas

El intento `rnf020-96a8258ba6f3` pasó las nueve pruebas Node del instrumento, creó el ambiente real y verificó las identidades y el aislamiento. Su smoke falló: el navegador cargó el origen HTTP interno sin considerarlo seguro, el envío quedó en «Enviando…» y no se persistió ningún mensaje ni notificación. El flujo utiliza `crypto.randomUUID()`, restringido a contextos seguros. Se conservan logs, capturas y reporte No evaluado; la corrida de 100 eventos quedó bloqueada automáticamente y la limpieza fue verificada. Un segundo intento (`rnf020-6d892281dd11`) verificó que la opción de origen confiable no bastó en este Chromium y se detuvo antes de enviar. Ambos ambientes se limpiaron. Se adoptó HTTPS interno con certificado efímero y comprobación explícita del contexto seguro; no se alteró el código de negocio.

El intento HTTPS `rnf020-3b1665381f98` superó el preflight de contexto seguro y detectó respuestas 403 en el envío de mensajes desde navegador; conservó los diez eventos smoke fallidos y bloqueó la etapa principal. La configuración CORS de la aplicación conserva por defecto los orígenes de desarrollo localhost. El override ahora declara exclusivamente `APP_CORS_ALLOWED_ORIGINS=https://frontend`, sin comodines, sin omitir CSRF y sin cambiar la configuración existente de k6. Este intento también se conserva y su ambiente se limpió.

## Archivos exactos para el próximo commit

Todos son nuevos; no incluir resultados, claves TLS temporales, `node_modules` ni `__pycache__`:

- `compose.rnf020.yaml`
- `scripts/rnf020/browser/Dockerfile`
- `scripts/rnf020/browser/package.json`
- `scripts/rnf020/browser/package-lock.json`
- `scripts/rnf020/evidence.cjs`
- `scripts/rnf020/finalize.cjs`
- `scripts/rnf020/measure.cjs`
- `scripts/rnf020/run.py`
- `scripts/rnf020/tests/instrument.test.cjs`
- `scripts/rnf020/tests/test_runner.py`
- `docs/pruebas/rnf020-visibilidad-e2e.md`

No se ejecutan comandos Git, no se cambia de rama y no se modifica código funcional de mensajería/notificaciones, Angular, CU-12, workflow OWASP ni los scripts/runner de k6 existentes.

## Hallazgo funcional: recepción omitida al enviar simultáneamente

El smoke `rnf020-0806b5c9a17d` persistió **10 mensajes y 10 notificaciones** reales, con respuestas de envío 200. Solo **5 de los 10 mensajes aparecieron en el destinatario** durante 12 s de observación; las 10 notificaciones sí aparecieron y se correlacionaron. Son cinco eventos por dirección: evidencia de fallo funcional, no una certificación estadística con 100 eventos. La etapa principal se bloqueó y la limpieza fue verificada.

La captura del vendedor muestra su mensaje propio ID 2 y omite el mensaje recibido ID 1, aunque ambos existen en MySQL. El polling solicita después `after=2` y recibe una lista vacía. El componente mezcla inmediatamente el envío propio y usa el último ID de su lista como cursor de recepción: un envío propio de ID mayor puede adelantar el cursor sobre un mensaje recibido aún no consultado. Esta explicación es una inferencia respaldada por el código, los IDs persistidos, las solicitudes de polling y la captura. No se modificó la lógica funcional para corregirlo, por restricción expresa del trabajo.

La medición principal de turnos alternos sigue los flujos A y B solicitados y debe interpretarse exclusivamente bajo ese modelo de interacción. **Un resultado favorable en turnos alternos no elimina el fallo simultáneo ni permite declarar RNF-020 cumplido de forma general.** El HTML resalta este hallazgo antes de presentar métricas; el modo `--simultaneous --smoke-only` permite repetirlo. Corregir el cursor y repetir ambos modelos corresponde a un trabajo funcional posterior autorizado.

## Límite de recursos observado

La ejecución alternada `rnf020-89a7b85022d9` aprobó el smoke y registró 84 eventos de la etapa principal. Se detuvo automáticamente al observar CPU agregada de Docker de 700,53 % de un núcleo (70,053 % de diez núcleos), manteniendo unos 1.925 MiB estimados disponibles. El ambiente se limpió. Se conservan los eventos como evidencia parcial, sin mezclarlos con otra corrida. Al interrumpirse antes del respaldo final, este intento conserva en `backing.json` solo el respaldo del smoke; por tanto sus eventos principales no acreditan la correlación persistida completa y permanecen No evaluado.

Para el siguiente intento se redujeron los límites propios de backend y navegador de 2 a 1,5 CPU y la frecuencia de lectura del DOM del instrumento de 25 a 50 ms. No cambian el polling funcional de 3/2 s ni los umbrales de 5.000 ms y 95 %. Aumentar el intervalo del observador agrega resolución conservadora, reduce interferencia del instrumento y no acelera la aplicación. El runner también intenta respaldar la persistencia en el bloque final, incluso si se interrumpe la etapa principal. Los resultados de configuraciones distintas se reportan separados.
