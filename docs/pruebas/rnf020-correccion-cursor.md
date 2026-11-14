# RNF-020: corrección puntual de recepción durante envíos simultáneos

## Causa demostrada

La evidencia histórica `rnf020-0806b5c9a17d` contiene diez mensajes persistidos, pero solo cinco observados en los destinatarios; las diez notificaciones sí llegaron a sus bandejas. El componente incorporaba la confirmación del envío propio a `mensajes` y usaba `mensajes.at(-1).id` como `after` del siguiente historial. Ejemplo: el comprador confirma ID 1; el vendedor recibe su confirmación propia ID 2 antes del polling y pide `after=2`. ID 1 queda fuera indefinidamente, aunque está persistido. La captura del vendedor y el respaldo SQL sostienen esta secuencia.

Antes de modificar el componente se creó y ejecutó `frontend/tests/conversaciones-concurrency.test.cjs`: fallaron cinco de siete casos, incluida la reproducción exacta `[2]` frente al esperado `[1,2]`, recuperación de ID menor tardío, varias páginas, cambio A → B → A y lectura adelantada por un envío propio. Pasaron los dos casos de borrador y respuesta antigua de lectura. Los logs se conservan como evidencia; después se agregaron dos casos adicionales de orden de respuestas y lectura.

La investigación no se limitó a la hipótesis inicial:

- `actualizando` evita dos pollings simultáneos, pero no separaba el envío propio del historial recibido.
- Un polling antiguo podía aplicarse después de abrir otra conversación y volver a la misma: comparar solo el ID no distingue aperturas A → B → A.
- El marcador de lectura también usaba el máximo ID visual, por lo que podía adelantar la lectura sobre un remoto todavía no recuperado.
- En el backend actual, `ConversationService.send` adquiere `own(..., true)` antes de insertar. El SQL usa `FOR UPDATE` sobre la conversación y mantiene el bloqueo hasta el commit de la transacción. Esto serializa los envíos de esa conversación; no se demostró inversión de commits como causa del caso histórico. Los IDs de conversaciones distintas pueden intercalarse sin que eso permita mezclar sus historiales.

## Corrección y alcance

Solo cambia el componente funcional `conversaciones.component.ts`. No cambian REST, servicios Angular, SQL, backend, migraciones, autenticación, autorización, claves idempotentes ni el intervalo de 3.000 ms.

El envío propio sigue apareciendo inmediatamente en el arreglo visual. La recuperación ahora **reconcilia la ventana de historial abierta** consultando las páginas reales más recientes y retrocediendo con `before` hasta alcanzar su inicio. No usa el máximo visual como cursor de recepción. La combinación existente por ID elimina duplicados y ordena los mensajes. Esto también recupera IDs menores que se confirmen después de una consulta previa: no depende exclusivamente de la monotonía de los commits. Si se abrió una conversación con menos de 50 mensajes, se reconcilia todo su historial; con un historial más largo se respeta la ventana inicial de 50 y el usuario puede ampliarla con Mensajes anteriores.

Una generación cambia en cada apertura. Se cancela el refresco anterior y se rechazan resultados de generaciones anteriores, incluso A → B → A. Un contador separado `confirmado` avanza solo con historial recuperado del servidor; los envíos propios no adelantan por sí solos el marcador de lectura. Las confirmaciones de lectura se aplican únicamente a la apertura vigente y usan un máximo para no retroceder por respuestas fuera de orden. Los borradores y claves de reintento conservan el comportamiento previo.

Reconciliar varias páginas aumenta el costo respecto de una consulta incremental única. Es una elección explícita de corrección con los contratos existentes: evita pérdidas sin introducir un endpoint nuevo. El costo crece con la ventana abierta y debe volver a medirse para conversaciones muy extensas. No se acortan intervalos ni se utilizan recargas, pausas artificiales del flujo funcional o respuestas interceptadas.

## Validación determinista

Los nueve casos ejecutan los métodos TypeScript reales mediante transpilación, RxJS real y servicios controlados con Subjects para ordenar las respuestas. La inyección y los efectos de Angular se sustituyen únicamente en este instrumento de unidad. No son métricas E2E ni respuestas HTTP falsificadas:

1. Remoto pendiente cuando se confirma primero un envío propio.
2. ID menor que aparece después de otro ya recuperado.
3. Más de 50 mensajes, orden y deduplicación.
4. Respuesta de polling tras A → B → A.
5. Confirmación de lectura de una apertura antigua.
6. Borradores y clave idempotente tras error y cambio de conversación.
7. El envío propio no adelanta lectura sin historial confirmado.
8. Polling iniciado antes del envío, respondido después, y recuperación siguiente.
9. Confirmaciones de lectura fuera de orden sin retroceso de `readThrough`.

```bash
npm ci --prefix frontend
node --test frontend/tests/conversaciones-concurrency.test.cjs
node --test scripts/rnf020/tests/comparison.test.cjs
```

## Regresión con aplicación y MySQL reales

El runner RNF-020 ejecuta además `functional.cjs` dentro de Chromium contra el Angular compilado y Spring/MySQL reales, antes de medir. El fixture ahora tiene dos productos y dos conversaciones para probar cambios de apertura, sin tocar una base existente.

Se comprueban login/rol de comprador, vendedor y cuenta ajena, rechazo de IDs ajenos, no leídos antes de abrir, mensaje en DOM y marcador de lectura, tres rondas de envíos rápidos simultáneos, orden/ausencia de duplicados respecto del historial persistido, borradores A → B → A, reintento real con la misma clave, cierre con rechazo 409 y reapertura.

No se ejecutó literalmente la suite histórica `frontend/e2e-cu10/conversations.spec.ts`: depende de su fixture propio, fallo inicial inyectado y pedidos específicos. La nueva regresión ejerce los contratos de CU-10 mencionados sobre la base exclusiva, sin interceptar HTTP. Adjuntos, reportes de contenido y otros CU no se declaran revalidados por estos casos. Su código no se modifica.

## Medición posterior y comparación

Se conservan los 100 eventos por dirección, el límite de 5.000 ms, el porcentaje mínimo 95 %, los timeouts dentro del denominador y los intervalos de polling originales. Los mensajes y las notificaciones se evalúan en cuatro grupos separados por modelo. Se repiten tanto turnos alternos como envíos simultáneos; un smoke favorable por sí solo no acredita el requisito.

```bash
python3 scripts/rnf020/run.py --simultaneous
python3 scripts/rnf020/run.py
node scripts/rnf020/compare-correction.cjs \
  artifacts/performance/<corrida-simultanea> \
  artifacts/performance/<corrida-alternada>
```

El comparador crea una carpeta nueva `rnf020-correction-<UUID>` con JSON y HTML autocontenido. Exige dos modelos completos con al menos 100 eventos por dirección, mismas fuentes de aplicación verificadas contra SHA-256 y árbol local, regresiones funcionales y limpieza aprobadas, y ausencia de pérdidas/no verificables. Mantiene separados los ocho grupos; no promedia canales o modelos. Los informes individuales conservan sus conclusiones por modelo; la comparación aporta la conclusión conjunta sin borrar evidencia anterior. Por prudencia, el runner de turnos alternos conserva exit 3 y el estado global individual No evaluado ante el antecedente histórico, aunque su `scenario_state` pueda ser Cumple. El comparador es la evaluación conjunta final: exit 0 si Cumple, 1 si No cumple y 3 si Parcial. Ejecutar los comandos por separado y consultar la comparación, sin interpretar un solo modelo como certificación integral.

El primer intento corregido `rnf020-ce12df8a6d48` aprobó la regresión real y recuperó los diez mensajes simultáneos del smoke. Se detuvo al respaldar el texto Unicode de la nueva regresión: el cliente MySQL no usaba UTF-8 explícito. El runner RNF-020 ahora ejecuta sus consultas con `--default-character-set=utf8mb4`, sin modificar el helper compartido de k6. Este intento se conserva como parcial y no se usa para aprobar el RNF.

## Resultados reales posteriores a la corrección

Mediciones del **14 de noviembre de 2026 (America/Bogota)**. El comparador generó `rnf020-correction-a8e370fe` con estado **Cumple** y exit 0: ambas versiones de fuentes y presupuestos de recursos coinciden, las regresiones pasaron y no quedaron muestras sin verificar. Este es el informe conjunto final; no depende de interpretar aisladamente el estado conservador del runner alternado.

| Modelo | Dirección | Canal | Muestras verificadas | ≤ 5 s | Media ms | P50 ms | P95 ms | Máximo ms | Fallos / no verificables |
|---|---|---|---|---|---|---|---|---|---|
| Simultáneo | Comprador → vendedor | Mensajes | 100/100 | 97 % | 2289.04 | 2099.53 | 3536.97 | 10816.43 | 0 / 0 |
| Simultáneo | Comprador → vendedor | Notificaciones | 100/100 | 97 % | 1947.10 | 1800.20 | 3311.99 | 11138.85 | 0 / 0 |
| Simultáneo | Vendedor → comprador | Mensajes | 100/100 | 97 % | 2657.03 | 2521.35 | 4117.95 | 11511.91 | 0 / 0 |
| Simultáneo | Vendedor → comprador | Notificaciones | 100/100 | 97 % | 1844.42 | 1532.79 | 3393.34 | 11607.57 | 0 / 0 |
| Alternado | Comprador → vendedor | Mensajes | 100/100 | 100 % | 1882.98 | 1636.21 | 3194.90 | 3584.99 | 0 / 0 |
| Alternado | Comprador → vendedor | Notificaciones | 100/100 | 100 % | 1327.61 | 1242.34 | 2102.62 | 3019.97 | 0 / 0 |
| Alternado | Vendedor → comprador | Mensajes | 100/100 | 99 % | 1280.60 | 879.23 | 3422.85 | 11300.26 | 0 / 0 |
| Alternado | Vendedor → comprador | Notificaciones | 100/100 | 100 % | 1591.19 | 1627.04 | 2390.95 | 4897.35 | 0 / 0 |

Se midieron **400 mensajes y 400 notificaciones** en total (800 observaciones de canal), sin combinar grupos para evaluar el 95 %. Hubo tres muestras tardías por grupo simultáneo y una de mensaje vendedor → comprador alternado. Se conservan sus latencias, incluido un máximo superior a 11 s: el requisito no exige máximo absoluto de 5 s, sino al menos 95 % dentro del límite. Ninguna muestra tardía se excluyó del denominador. La tasa de errores funcionales y la cantidad de mensajes omitidos fueron cero en ambas corridas.

El smoke anterior había mostrado 5/10 mensajes visibles y 10/10 notificaciones. Después: 200/200 mensajes y 200/200 notificaciones verificables en simultáneo, y lo mismo en turnos alternos, con 100 eventos por dirección en cada modelo. El tamaño anterior era smoke (cinco por dirección), no una corrida comparable de 100: se demuestra desaparición de las pérdidas y cumplimiento actual, sin atribuir una mejora porcentual de rendimiento a muestras o presupuestos distintos.

- **Simultáneo · `rnf020-9419adf09957`:** 2026-11-14 00:17:59 a 2026-11-14 00:27:16, duración total 556.91 s. 58 muestras de recursos; mínimo estimado disponible 1659 MiB; máximo CPU agregado 514.07 % de un núcleo, sobre diez núcleos. La etapa principal terminó completa y con exit 0 del navegador. MySQL respaldó 219 mensajes y 219 notificaciones: 200 de medición, 10 de smoke y 9 de regresión, en cada base. Cero contenedores, redes y volúmenes del proyecto tras la limpieza.
- **Alternado · `rnf020-b32ede053016`:** 2026-11-14 00:27:56 a 2026-11-14 00:38:41, duración total 645.46 s. 78 muestras de recursos; mínimo estimado disponible 1712 MiB; máximo CPU agregado 280.28 % de un núcleo, sobre diez núcleos. La etapa principal terminó completa y con exit 0 del navegador. MySQL respaldó 219 mensajes y 219 notificaciones: 200 de medición, 10 de smoke y 9 de regresión, en cada base. Cero contenedores, redes y volúmenes del proyecto tras la limpieza.

Entorno: Docker Linux/aarch64, 10 CPU lógicas, 8.319.504.384 bytes de memoria en la VM; Engine 29.8.0, Compose 5.5.1, Playwright 1.63.0/Chromium 153.0.8010.12, Java 21.0.13/Spring Boot 4.1.1, MySQL 8.4.11 y migraciones V1–V47 verificadas. Las cuotas propias fueron backend/Chromium 0,75 CPU cada uno, MySQL 0,5 y Nginx 0,125. El computador siguió compartido con otros contenedores que no se modificaron.

Las imágenes se reconstruyeron por proyecto y sus IDs difieren; se conservan en `isolation.json`. La comprobación conjunta verifica las mismas fuentes, dependencias declaradas y recursos, sin afirmar identidad binaria de las imágenes. La verificación adicional quedó en `verification.json`.

**Pruebas automatizadas:** nueve casos deterministas del componente, diez casos del instrumento en Chromium, tres del comparador y tres de aislamiento/limpieza del runner: **25 casos aprobados**. La regresión funcional real pasó en las dos bases y las dos corridas volvieron a pasar el smoke antes de medir. La prueba roja inicial se conserva en `regression-before.log`; la verde del componente, en `regression-after.log`.

Informes: [`rnf020-correction-executive.html`](../../artifacts/performance/rnf020-correction-a8e370fe/rnf020-correction-executive.html) y [`rnf020-correction-summary.json`](../../artifacts/performance/rnf020-correction-a8e370fe/rnf020-correction-summary.json). Los informes, originales por evento, SQL de respaldo, hashes, logs y recursos de cada modelo están en sus carpetas `rnf020-9419adf09957` y `rnf020-b32ede053016`. Exportar las tres carpetas juntas para conservar los enlaces. La comparación incluye además copia de la evidencia histórica del smoke y una captura, sin borrar los originales.

El estado **Cumple** se limita a mensajes de texto y bandeja interna en estos dos modelos, dos cuentas participantes y una conversación principal por ejecución. No acredita push del navegador, adjuntos, CU distintos, carga de 50/100 VU, conversaciones muy extensas ni estabilidad prolongada. La interfaz mantiene su ventana inicial de historial y navegación a mensajes anteriores.

Para sustentar: mostrar primero la pérdida ID 1/confirmación propia ID 2 en la evidencia antigua, después la prueba determinista roja/verde y la separación del historial visual respecto del confirmado. Abrir la comparación de ocho grupos, señalar los eventos tardíos sin ocultarlos y cerrar con la limpieza y los límites del laboratorio.

## Archivos para un commit manual

Modificados:

- `frontend/src/app/mensajeria/components/conversaciones.component.ts`
- `scripts/rnf020/run.py`
- `compose.rnf020.yaml`
- `scripts/rnf020/evidence.cjs`

Nuevos:

- `frontend/tests/conversaciones-concurrency.test.cjs`
- `scripts/rnf020/functional.cjs`
- `scripts/rnf020/compare-correction.cjs`
- `scripts/rnf020/tests/comparison.test.cjs`
- `docs/pruebas/rnf020-correccion-cursor.md`

Los artefactos están ignorados por Git y deben exportarse aparte. No se ejecutaron comandos Git, actualizaciones Angular ni cambios en k6, OWASP, CU-12 o backend.

## Condiciones y freno de recursos

El intento `rnf020-005fc2cc5e35` pasó la regresión y el smoke completo corregido. La medición principal se interrumpió al alcanzar Docker 937,29 % de CPU de un núcleo (93,729 % de diez núcleos). Se conservaron datos y respaldo en el bloque final, se limpió el ambiente y no se aprobó el RNF con esa corrida.

El siguiente presupuesto exclusivo limita backend y Chromium a 0,75 CPU cada uno, MySQL a 0,5 y Nginx a 0,125 (2,125 CPU nominales). El runner verifica antes de iniciar que el consumo existente más ese presupuesto quede por debajo del freno de 70 %, además de exigir memoria suficiente. Durante la prueba sigue deteniéndose ante el mismo límite. No se modifica ningún contenedor ajeno. La observación del DOM del instrumento pasa de 50 a 100 ms para reducir interferencia; esto agrega resolución conservadora y no cambia los temporizadores funcionales de 3.000/2.000 ms ni los umbrales oficiales. La comparación exige igual presupuesto de recursos en ambas corridas.
