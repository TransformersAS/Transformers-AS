# Auditoría CU-11 — Consultar pedidos y solicitar su cancelación

Especificación oficial: adjuntob1c85996-bfba-4594-a96b-e2caf056bfc8. Worktree exclusivo Transformers-AS-demos-cu07-cu11; feature/demos-cu07-cu11 leída en metadatos sin Git. Auditoría previa a cambios de instrumentos. Baseline: `artifacts/cu11-audit/audit-2c3f9c6bb986/baseline.json`.

## Evidencia previa y nivel real de pruebas

- **D0**: `e2e-sustentacion/cu11.spec.ts` llama `e2e-demo/account-order-flows.cancellationFlow`: login UI, un pedido Confirmado, Otro vacío/espacios bloquean, explicación, cancelación completa, respuesta CANCELLED/REFUNDED/COMPLETED, recarga y «Resultado simulado». Chrome real/Spring/MySQL;350ms/1200ms, sin aserción de inventario, fecha/motivo SQL, notificaciones, filtros, PDF, logística ni fallos. No hay prueba E2E CU11 propia en `e2e-real/`.
- **B1** `OrderOwnershipIntegrationTests`: HTTP MockMvc/MySQL real; listado propio/vacío, detalles,401/403/404/CSRF, `cancellationChangesOnlyTheOwnedOrderAndRepeatReturns409`, `simultaneousCancellationRequestsPerformExactlyOneTransition`, `cancellationRejectsNonCancelableStatesWithoutEffects`, `otherRequiresExplanationAndPersistsIt`.
- **B2** `BuyerOrderFilterTests`: filtros MySQL (estado, límites inclusivos de días, combinación, cuenta ajena). `BuyerOrderHistoricalDetailTests`: `allTenFieldsSurviveChangesToCurrentData`, `cancellationKeepsHistoricalValuesAndRestoresInventoryOnlyOnce`; compras de prueba por adaptador simulado, no compras externas reales.
- **B3** `BuyerOrderHistoryTests`: secuencia real, eventos con igual fecha, sin inventar transiciones y consulta sin escrituras. `BuyerOrderReceiptTests`, `OrderReceiptPdfTests`: PDF real con PDFBox/texto/bytes, historia, autorización; el segundo es unitario sin servidor. `CancellationPersistenceIntegrationTests`, `CancellationInventoryOutcomeTests`: unicidad, cantidades exactas, productos ausentes, carreras comprador/estado, no duplicaciones.
- **B4** `RequestRefundUseCaseTests`: adaptador de reembolsos simulado y estados OK/PENDING/UNAVAILABLE, no dinero bancario. `MercadoPagoCheckoutCancellationTests`: protección de checkout cerrado ante aprobación tardía y carreras; es el checkout previo a pedido, no prueba por sí solo el paso18 de CU11 sobre un pedido ya existente.
- **F**: `frontend/tests/mis-pedidos.test.cjs` transpila métodos reales con Angular/DI/servicios controlados; sin navegador/backend. `frontend/e2e/mis-pedidos-{cancelacion,comprobante,filtros,historial,historico,reembolsos}.spec.ts` son E2E con `page.route`, no persistencia real. Se registrará el resultado efectivo al ejecutarlas; no se asume aprobación por presencia.
- La logística de consulta pertenece a CU24: fixtures pueden preparar shipments/eventos y CU11 mostrarlos. No se afirma crear transporte real ni ejecutar CU03/CU19/CU23/CU24.

## Matriz RF previa

| RF | Implementado | Prueba automatizada existente | Visible D0 | Evidencia exacta / nueva automatización requerida | Brecha o límite |
|---|---|---|---|---|---|
|026 Historial/filtros|Sí|B1 listIncludesOnlyOwnOrders/listIsEmpty; B2 filtros; F simuladas|Listado1, sin filtros|UI estado/fecha, vacío y ajeno404; backend lista propia|No brecha detectada en consultas|
|027 Detalle histórico|Sí|B2 allTenFieldsSurviveChangesToCurrentData; F historico|Parcial,1 línea|Dos productos/cantidades/precios, descuento/envío/total, snapshot dirección/vendedor y datos actuales distintos|No confundir fixtures con compra real; procedencia no verificada sigue UNKNOWN|
|028 Seguimiento|Sí|B3 secuencia; pruebas logísticas existentes|No explícito|Interno sin transporte y fixtures de recogido/en camino/novedad/fallido/entregado/retornado|No ejecutar operaciones logísticas de otros CU|
|029 Comprobante|Backend/descarga sí; visor integrado limitado en Chrome|B3 receipt/PDF unitario; F comprobante|No|Preview y descarga reales, validar bytes y contenido del pedido|No es factura fiscal ni recibo bancario|
|030 Acciones por estado|Sí|B1 cancellationRejects...; F cancelacion|Elegible/cancelado|Confirmed/Preparation permiten; READY y posteriores no, orientación devolución|Orientar sin iniciar CU19|
|054 Elegibilidad|Sí, revalidación server/CAS/sin envío|B1 y B3 carreras|Sólo CONFIRMED|IN_PREPARATION, estados posteriores, cambio a READY antes de confirmar|Race se preparará sólo en DB propia; no simular respuestaHTTP|
|055 Cancelación automática|Sí, sin aprobación vendedor|B1 otherRequires.../repeat/concurrent; B3 unicidad|Otro+confirmación/recarga|Motivo/fecha SQL, sin vendedor actuando, duplicada409 y efectos únicos|No se altera negocio|
|056 Inventario|Sí para productos existentes; ausentes se registran como omitidos|B2 cancellationKeeps...; B3 CancellationInventoryOutcomeTests|No SQL|Stock antes/después por línea y repetición sin nueva reposición|Producto eliminado no se recrea ni se inventan unidades|
|057 Reembolso/avisos|Sí reembolso tras commit; parcial para pago pendiente acoplado|B4 y B1; D0 completado simulado|COMPLETED simulado|OK/PENDING/UNAVAILABLE en adaptador existente, UI honesta, ambos avisos y fallo externo con CANCELLED persistido|Simulador no es devolución bancaria. CancelOrderUseCase no cierra checkout pendiente; paso18/A9 requiere distinguir flujo previo de checkout|

## Matriz21 pasos previa

| Paso | Implementado | Prueba existente | Visible D0 | Evidencia / adición necesaria | Brecha o límite |
|---|---|---|---|---|---|
|1 Mis pedidos|Sí|D0/F|Sí|Login UI y apertura con pausa|No|
|2 Historial propio|Sí|B1/B2/F|Parcial1pedido|Lista propia y vacío|No|
|3 Filtrar/seleccionar|Sí|B2/F filtros|Sólo selección|Estado y fechas en UI|No|
|4 Todos los detalles|Sí|B2/F histórico|Parcial|Dos líneas, montos y entrega exactos|UNKNOWN no se convierte artificialmente en procedencia verificada|
|5 Estado e historia|Sí|B3/F historial|Estado sí|Historial cronológico real y sin escritura al consultar|No|
|6 Eventos logísticos|Sí consulta|Pruebas logística/B3|No|Fixtures consultados por UI para seis estados/eventos|No representa ejecución de CU24|
|7 PDF|Parcial en Chrome|B3/F comprobante|No|Preview/descarga y contenido real|No factura fiscal|
|8 Habilitar cancelación|Sí|B1/F|CONFIRMED sí|También Preparation y ausencia posteriores|No|
|9 Cancelar completo|Sí|D0/B1|Sí|Dos líneas restauradas, sin cancelación parcial|No|
|10 Motivos con Otro|Sí|B1/F/D0|Sí|Select real y confirm deshabilitada vacío|No|
|11 Explicación|Sí|B1/F/D0|Sí|Vacío/espacios, texto válido y registroSQL|No|
|12 Consecuencias|Sí|F/D0 no aserción|Parcial|Note definitiva/reembolso separado/plazo no garantizado|No|
|13 Confirmar|Sí|D0/B1|Sí|POST real200|No|
|14 Revalidar estado|Sí|B1/B3 carreras|No|READY desde fixture antes del clic,409 y detalle actualizado|No se opera preparación/despacho de CU23|
|15 CANCELLED|Sí|D0/B1|Sí|UI/API/SQL y recarga|No|
|16 Stock|Sí|B2/B3|No|Delta exacto por cantidades y duplicada sin delta|No para producto físicamente ausente; omisión trazada|
|17 Reembolso aprobado|Sí simulador|B4/B1|Sí simulado|Tres modos en ambientes separados|No dinero real|
|18 Cerrar pago pendiente y evitar confirmación tardía|Parcial/no acoplado en CU11|B4 checkout cerrado previo a pedido|No|No se puede representar PENDING en OrderPaymentStatus; documentar brecha de tardía|CancelOrderUseCase no orquesta cancelación del proceso checkout; no atribuir garantía de otro flujo|
|19 Fecha/motivo/historia|Sí|B1/B3|No SQL|Registro BUYER/OTHER/details/fecha y transición única|No|
|20 Avisos ambos/externo|Sí|B1/notificaciones existentes|No|UI comprador/vendedor y SQL; adaptador externoUNAVAILABLE|Externo simulado de prueba, no productivo|
|21 Confirmación/reembolso|Sí|D0/F/B4|Sí COMPLETED|Pendiente/error no revierten; UI «Resultado simulado» no promete banco|No ocultar resultado de simulación|

## Matriz A1–A14 previa

| Alterno | Implementado | Prueba existente | Visible D0 | Evidencia / adición necesaria | Brecha o límite |
|---|---|---|---|---|---|
|A1 Sin pedidos|Sí|B1 listIsEmpty/F|No|Cuenta sintética vacía real|No|
|A2 No cancelable|Sí|B1 estados/F|No|READY/PICKED/TRANSIT/DELIVERED y posteriores por UI/API|No|
|A3 Despachado/devolución|Sí orientación|F/B1|No|Texto de orientación en tránsito/entregado, sin iniciar Devolver|No CU19|
|A4 Race estado|Sí|B1/B3|No|Editar sólo fixture propio aREADY antes de confirmar,409, sin efectos|No claim de transporte real|
|A5 Automática|Sí|B1/D0|Sí|Nadie aprueba como vendedor; estado/SQL inmediata|No|
|A6 Otro|Sí|B1/F/D0|Sí|Confirm disabled sin explicación; registroconmotivo|No|
|A7 Pendiente|Sí|B4/F|No|Gateway PENDING, CANCELLED y REFUND_PENDING persistidos|UI de seguimiento puede etiquetar resultado simulado; registrar ambos niveles|
|A8 Error reembolso|Sí|B4/F|No|UNAVAILABLE, refundsFAILED y cancelación no revertida|Sin afirmar reversión bancaria|
|A9 Pago pendiente/tardío|Parcial|B4 checkout cancellation separado|No|No existe pedido con paymentStatusPENDING en el contrato; no preparar un fixture inválido|No fabricar una confirmación con SQL ni extender CU03|
|A10 Sin eventos|Sí|B3/F|No|Historia interna y ausencia de componente logístico antes del despacho|No inventar transporte|
|A11 Novedad/fallido|Sí consulta|Pruebas CU24 de tracking|No|Fixtures con eventos previos conservados, UI y API|Consulta de estados preparados, no ejecución logística|
|A12 Retorno|Sí consulta|Tracking tests|No|Estado retornado y secuencia anterior, cierre del tracking|No implica reembolso automático|
|A13 Fallo externo|Sí|Notificaciones tests/B1|No|Adaptador simuladoUNAVAILABLE, avisos internos y CANCELLED intactos|No proveedor real|
|A14 Error consulta|Sí reintento|F componentes/E2E simuladas|No|Fallo DB controlado propio paraGET, errorUI, retirarfallo y actualizar|No interceptacionesHTTP|

## Decisiones posteriores a la auditoría

Principal con dos líneas, historia interna, Otro vacío/espacios, consecuencias, cancelación, resultado simulado, SQL stock/motivo/fecha y recarga. Complementos: `history`, `detail`, `states`, `race`, `consistency`, `refund-pending`, `refund-error`, `external`, `query`.

Para no reemplazar operaciones por fixtures, los modos reales existentes de los adaptadores se configuran al arrancar. La modalidad conjunta ejecutará tres proyectos desechables sucesivos: baseOK (siete escenarios), reembolsoPENDING y reembolsoUNAVAILABLE. Cada escenario podrá lanzarse solo. No se cambia configuración de servicios compartidos ni se recrea un backend ajeno. El proveedor externo simulado fallará en extras; principal usaOK. Toda evidencia financiera distinguirá simulación de acreditación externa.

## Hallazgo al validar el primer fixture

La base rechazó payment_status=PENDING con chk_orders_payment_status (intento sust-cu11-ebcfe92ff8ee, sin navegador). OrderPaymentStatus sólo admite APPROVED/REFUND_PENDING/REFUNDED y el checkout sólo genera pedidos aprobados. Se retiró el escenario de pedido con pago pendiente: paso18/A9 no se puede demostrar como CU11, ni fabricar mediante SQL una confirmación tardía. Las24 pruebas existentes de checkout cerrado sí se ejecutaron, pero no sustituyen ese criterio ni se atribuyen a CU11. Se conserva el intento fallido y su limpieza. También se ajustó el fixture de shipments a su contrato real CREATED; los estados de transporte se consultan en orders/eventos, sin modificar CU24.

## Cobertura ampliada: evidencia ejecutable exacta

[Índice local de resultados](../../artifacts/cu11-audit/audit-2c3f9c6bb986/validation.md). Los estados aprobados y duraciones se completan con los resultados efectivos al terminar; no se infieren por existir la prueba. Las referencias siguientes indican nombres de testigos en `cu11-checks/` de la ejecución correspondiente.

**P1/P2/P4/P5/P6**: pasos `test.step` de principal (el opcional P3 de visor se retiró tras revisar las capturas); capturas finales01/02/02b/03/05/06/07/08; la04 del visor se conserva sólo en intentos anteriores. **H** history, **D** detail, **E** states, **C** race, **U** consistency, **RP** refund-pending, **RE** refund-error, **N** external y **Q** query.

### Nueve RF: cobertura adicional y límites

| RF | Prueba añadida | Evidencia concreta | Falta / brecha restante |
|---|---|---|---|
|026|P1/H|H cuenta vacía,3 propios, estado da1 y rango octubre da2; ajeno detalle/PDF/cancelación404; `history-isolation`, `history-read-only`|No se atribuyen a la demo todos los límites microsegundo de B2|
|027|P2/D|API total30000, descuento3000, envío5000, productos cantidades2+1, dirección y vendedor históricos diferentes de datos actuales; `detail-before`/`detail-after-read` con digest intacto|Snapshots de fixture tienen procedencia UNKNOWN; no se inventa una compra capturada ni acreditación bancaria|
|028|P2/D|Historial interno sin componente logístico; D eventos previos recogido/en camino/novedad/fallido, entrega y retorno visibles; `detail-tracking-scope`|Sólo consulta eventos preparados; no ejecución CU24 ni transporte real|
|029|D|GET PDF real application/pdf y firma%PDF, pero iframe con error en Chrome; descarga real `receipts/pedido-ID.pdf`; texto con PDFBox existente contiene pedido, nombres, dirección y montos; `detail-pdf`|Comprobante generado al consultar, no factura fiscal ni documento bancario. Lector propio usa UTF-8 explícito|
|030|P4/E|CONFIRMED principal, IN_PREPARATION cancelada; READY/PICKED_UP/IN_TRANSIT/DELIVERED/RETURNED_TO_SELLER sin botón y API409; orientación devolución sin pulsar Devolver; `states-denied`, `states-preparation-cancelled`|No se ejecuta CU19|
|054|P5/E/C|POST de cancelación elegible200; C estado preparadoREADY antes de confirmar,409 ORDER_INVALID_TRANSITION y detalle actualizado; `race-before`, `race-not-cancelled`|Fixture representa otro actor concurrente; B3 cubre carreras reales de transacciones|
|055|P4/P5/U|Motivo vacío/Otro sin texto/espacios deshabilitan; consecuencias reales; cancelación automática sin aprobación; reasonOther1 y historyCancelled1; repetición409 conserva cancellationDigest y efectos únicos; `consistency-original-preserved`|No se altera lógica de vendedor ni aprobación|
|056|P5/U/E|SQL stockA20→22 y stockB20→21 para cantidades2+1; duplicada mantiene22/21; `main-before`, `main-after`, `consistency-duplicate-rejected`|Ausencia física de producto queda en B3; no se reconstruye inventario inventado|
|057|P5/RP/RE/N|Main CANCELLED/REFUNDED/refundCOMPLETED con referenciaSIM; RP CANCELLED/REFUND_PENDING/refundPENDING; RE CANCELLED/REFUND_PENDING/refundFAILED/error registrado; N avisos comprador/vendedor y2 externosFAILED; testigos de cada escenario|Simulación explícita, no devolución bancaria. Paso18/A9 no representable como pedido con pago pendiente; checkout separado no suple ese criterio|

### Los21 pasos: automatización añadida individual

| Paso | Evidencia añadida | Resultado que puede afirmarse |
|---|---|---|
|1|P1 login UI y Mis pedidos|Comprador autenticado real|
|2|P1/H lista propia, vacía y ajeno404|No mezcla cuentas|
|3|H select estado y fechas, P2 selecciónID|Filtros con1/2 resultados esperados|
|4|P2/D dos líneas, descuentos/envío/total, snapshotentrega y vendedor|API/UI exactas y datos históricos separados de los actuales|
|5|P2 historial y estado, D digest de lectura|Historia procede del backend|
|6|D timelines preparadas de incident/delivered/returned|Seis tipos observados al consultar, sin transporte ejecutado|
|7|D descarga y parser PDFBox; visor opcional omitido de principal por error de render|PDF real con contenido correcto; visualización integrada no acreditada|
|8|P4 y E preparación frente a posteriores|Habilitación y bloqueo reales|
|9|P4 Cancelar pedido y P5 reposición de ambas líneas|Cancelación completa|
|10|P4 select de motivos y Otro|Motivos reales, confirmación vacía deshabilitada|
|11|P4 vacío/espacios, texto válido; main-after reasonOther1|Explicación obligatoria y registroSQL|
|12|P4 note Consecuencias: definitiva, dinero separado, sin plazo garantizado|Mensaje visible y pausa de exposición|
|13|P5 clic y POST200|Sin intervención manual|
|14|C cambia fixtureREADY entre formulario y confirmación;409|Revalidación del estado actual; no simula respuestaHTTP|
|15|P5 CANCELLED, P6 recarga y SQL|Persistencia de estado|
|16|P5/U stock+2/+1 exactamente; duplicada sin aumento|Sin doble restitución|
|17|P5/RP/RE adaptador existente con tres modos|Solicitud y estados simulados, no banco|
|18|No escenario de pedidoPENDING|**No cubierto como CU11**: contrato/DB no admiten ese estado; B4 sólo prueba checkout previo cerrado|
|19|main-after reasonOther1/fecha no nula/historyCancelled1; U digest original|Motivo/fecha/transición única conservados|
|20|N avisos reales de ambas cuentas y SQL2; externosFAILED|Fallo externo simulado no elimina actualización interna|
|21|P5 exito y Resultado simulado; RP/RE mensajes inmediatos y recarga|Cancelación persiste con reembolso completado/pendiente/fallido según simulador|

### A1–A14: automatización añadida individual

| Alterno | Prueba y testigo | Evidencia / falta restante |
|---|---|---|
|A1|H `history-isolation`|Cuenta sin pedidos muestra mensaje y0 acciones|
|A2|E `states-denied`|Cinco estados posteriores sin botón,409, sin cancelación/reembolso ni cambio de stock|
|A3|E tránsito/entregado|Orientación de cuándo comprobar elegibilidad de devolución; no se inicia CU19|
|A4|C `race-not-cancelled`|READY antes del clic final;409 y detalle actualizado; stock20/20,0 cancelaciones/refunds|
|A5|P5/E/U|Procesa directamente sin vendedor actuando; CANCELLED y registroBUYER|
|A6|P4 `main-05-otro-bloqueado`|Vacío y espacios mantienen botón deshabilitado; texto válido habilita|
|A7|RP `refund-pending-persisted`|OrderCANCELLED/paymentREFUND_PENDING/refundPENDING, inventario22/21; recarga no revierte|
|A8|RE `refund-error-persisted`|OrderCANCELLED/paymentREFUND_PENDING/refundFAILED/error registrado, inventario22/21|
|A9|No se implementa una versión falsa|PENDING no es un OrderPaymentStatus válido. Paso18 no aplicable/implementado en esta ruta. B4 checkout separado24 pruebas pasaron, sin atribuirlas a cancelación de pedido|
|A10|P2/D `detail-03-sin-transporte`|Sólo historia interna y ausencia de componente logístico, sin inventar eventos|
|A11|D incident|Cuatro eventos conservados y ordenados, incluyendo novedad e intento fallido|
|A12|D returned|Retornado al vendedor y eventos previos; no se atribuye reembolso por retorno|
|A13|N `external-notices`/`external-scope`|CANCELLED y avisos internos ambos; adaptador simuladoUNAVAILABLE registra2 fallos tras commit|
|A14|Q `query-http`/`query-recovered`|GET500 real por recursoDB propio temporalmente inaccesible; alerta/reintento; restaurado conserva hashes de pedido/ítems, stock20/20,0 cancelaciones/refunds|

## Uso exacto y ritmo

Ejecutar desde `/Users/sofiamantilla/Documents/GitHub/Transformers-AS-demos-cu07-cu11`:

```sh
npm --prefix frontend run demo:cu11
npm --prefix frontend run demo:cu11:extras
npm --prefix frontend run demo:cu11:extras -- --scenario history
npm --prefix frontend run demo:cu11:extras -- --scenario detail
npm --prefix frontend run demo:cu11:extras -- --scenario states
npm --prefix frontend run demo:cu11:extras -- --scenario race
npm --prefix frontend run demo:cu11:extras -- --scenario consistency
npm --prefix frontend run demo:cu11:extras -- --scenario refund-pending
npm --prefix frontend run demo:cu11:extras -- --scenario refund-error
npm --prefix frontend run demo:cu11:extras -- --scenario external
npm --prefix frontend run demo:cu11:extras -- --scenario query
```

Por defecto Chrome visible, viewport1440×1000, slowMo900ms y pausa2500ms por captura importante. Sólo presentación: no se ralentizan suites normales/CI/RNF. Configuración alternativa:

```sh
CU11_DEMO_SLOW_MS=600 CU11_DEMO_PAUSE_MS=2000 npm --prefix frontend run demo:cu11
CU11_DEMO_SLOW_MS=100 CU11_DEMO_PAUSE_MS=0 npm --prefix frontend run demo:cu11:extras -- --scenario detail
```

Las pausas son exposición; éxito depende de UI, HTTP y SQL. Conjunta: tres proyectos sucesivos y nuevos, base con siete escenarios/refundOK, luego refundPENDING y refundUNAVAILABLE. El proveedor externo simulado falla en extras y funciona en principal. Cada hijo valida Flyway y DB vacía, usa redes internas y puertos loopback efímeros, y limpia sólo su propio proyecto antes del siguiente. El orquestador agrega reportes, no posee contenedores. No se usan puertos/bases/volúmenes compartidos ni HTTP interceptado en demos.

Q renombra temporalmente únicamente la tabla orders de su DB desechable para provocarGET500 y la restaura en finally; no modifica datos ni migraciones. C prepara sólo un estado concurrente propio, sin operar CU23/CU24. La logística se prepara por SQL; su consulta no representa despacho/transporte real. Productos/usuarios/pedidos/PDF son sintéticos.

## Regresiones ejecutadas y clasificación

Backend, en copia temporal de la fuente original:

```sh
./mvnw -B -Dtest=OrderOwnershipIntegrationTests,BuyerOrderFilterTests,BuyerOrderHistoricalDetailTests,BuyerOrderHistoryTests,BuyerOrderReceiptTests,OrderReceiptPdfTests,CancellationPersistenceIntegrationTests,CancellationInventoryOutcomeTests,RequestRefundUseCaseTests,MercadoPagoCheckoutCancellationTests -Djacoco.skip=true test
```

Diez suites/114 casos aprobados, sin fallos/errores/omitidos: incluye5 unitarios PDFBox sin servidor y casos Spring/MySQL reales; adaptadores de pago/logística/notificaciones controlados según el test. No se sobrescribieron informes originales: se extrajeron XML sanitizados/resumen y se retiró staging.

Frontend: seis archivos E2E existentes de Mis pedidos ejecutados en Chrome normal headless, build de producción de la misma fuente y puerto propio libre:38/38 aprobadas. Usan `page.route`; no se presentan como backend/MySQL reales. Comando en stagingfrontend, con configuración normal y reporte sanitizado:

```sh
node node_modules/@playwright/test/cli.js test mis-pedidos-cancelacion.spec.ts mis-pedidos-comprobante.spec.ts mis-pedidos-filtros.spec.ts mis-pedidos-historial.spec.ts mis-pedidos-historico.spec.ts mis-pedidos-reembolsos.spec.ts --project=chrome --workers=1
node --test tests/mis-pedidos.test.cjs
```

La segunda orden obtuvo4 aprobadas/39 fallidas de43: fixture antiguo sin sustitución de `ContactarVendedorComponent`, antes de probar el comportamiento del componente. Se conserva sin editar; no se alteran CU10 ni dependencias para ocultarlo. No se afirma aprobación total de todas las pruebas ejecutadas. Las nuevas demos son recorridos completos reales con loginUI, CSRF, backend y MySQL; las operaciones de pagos externos siguen siendo simuladas.

## Límite visual detectado al revisar capturas

En las primeras principales el endpoint devolvió application/pdf con firma válida y el iframe apareció, pero la captura main-04-comprobante muestra el visor Chrome con error. No se afirma que el contenido haya sido visualizado correctamente. RF029 queda parcial en este ambiente: descarga y contenido PDF reales sí, render integrado no. Se retira ese paso opcional de la principal final para no mostrar un visor fallido durante la sustentación; el comprobante se mantiene en detail mediante descarga real y parser PDFBox. No se modifican CSP/SRI, headers ni lógica productiva. Se conservan esas capturas y se repiten dos principales del recorrido final.


## Resultados finales efectivos

Dos principales del recorrido final aprobadas en Chrome visible: 45.365s y 45.474s de test; invocaciones completas 86.579s y 86.307s. Viewport1440×1000,900ms slowMo y2500ms exposición. Se omitió el visor opcional fallido y se añadió una pausa específica en dirección/productos; no se eliminó ninguna capacidad productiva.

Nueve complementos aprobados individualmente100ms/0ms y en conjunta900ms/2500ms. Conjunta final en3 proyectos: 248.852s de tests, 253.790s sumando procesos navegador y 392.942s de invocación completa con construir/iniciar/limpiar. No se confunden tiempos de infraestructura con exposición.

| Complemento | Individual100/0 (s) | Conjunto900/2500 (s) | Resultado |
|---|---:|---:|---|
| history | 6.859 | 31.342 | Aprobado en ambas modalidades |
| detail | 6.991 | 38.421 | Aprobado en ambas modalidades |
| states | 6.853 | 43.300 | Aprobado en ambas modalidades |
| race | 4.890 | 20.311 | Aprobado en ambas modalidades |
| consistency | 7.041 | 23.292 | Aprobado en ambas modalidades |
| external | 7.027 | 28.131 | Aprobado en ambas modalidades |
| query | 4.440 | 18.169 | Aprobado en ambas modalidades |
| refund-pending | 4.554 | 23.290 | Aprobado en ambas modalidades |
| refund-error | 4.562 | 22.596 | Aprobado en ambas modalidades |

Backend:114/114 aprobadas, sin errores/fallos/omitidas, en10 suites;5 casos unitarios de PDFBox y pruebas con Spring/MySQL/adaptadores controlados. Frontend E2E con HTTP simulado:38/38 aprobadas, seis archivos existentes, Chrome normal headless. Suite unitaria antigua Mis pedidos:4/43 aprobadas y39 fallidas por fixture sin ContactarVendedorComponent; se conserva sin editar y no se declara que todas las pruebas ejecutadas están verdes.

| Regresión de demo conservada | Test visible(s) | Resultado |
|---|---:|---|
| CU07 | 43.183 | Aprobada900/2500 |
| CU08 | 51.824 | Aprobada900/2500 |
| CU09 | 36.298 | Aprobada900/2500 |
| CU10 | 74.946 | Aprobada900/2500 |

Los26 proyectos tienen cleanup aprobado y consulta Docker posterior vacía para contenedores/redes/volúmenes propios; tampoco quedan contenedores etiquetados Testcontainers. Los dos orquestadores sólo agregan reportes, sin proyecto Docker propio. No se ejecutó Git ni se borraron evidencias previas. Se preservan el fixturePENDING rechazado, el primer detalle fallido por codificación del lector, la primera conjunta fallida por cierre redundante del visor y las primeras principales que permitieron detectar visualmente el error del iframe.

Alcance:3 archivos existentes modificados y10 nuevos de instrumentos/documentación CU11;1482 hashes iniciales protegen el resto. package.json sólo añade demo:cu11:extras. No se modificó código de negocio, controladores, migraciones, otros CU, RNF, workflows ni dependencias.

| Tipo | Archivo | Propósito |
|---|---|---|
| Modificado | `scripts/demos/run.py` | Ramas CU11 de fixture/configuración/ritmo/escenario y grupos, sin suprimir opciones anteriores. |
| Modificado | `frontend/package.json` | Sólo agrega demo:cu11:extras; principal y scripts anteriores intactos. |
| Modificado | `frontend/e2e-sustentacion/cu11.spec.ts` | Principal visual con detalle/entrega, Otro, cancelación, inventario y recarga; visor opcional retirado por limitación Chrome. |
| Nuevo | `docs/ejecucion/auditoria-demo-cu11.md` | Matrices9 RF/21 pasos/A1–A14, cobertura, resultados y brechas. |
| Nuevo | `compose.cu11-presentation.yaml` | Sólo modos de adaptadores simulados existentes en el proyecto CU11 propio. |
| Nuevo | `scripts/demos/cu11-seed.sql` | 22 cuentas,20 productos y22 pedidos sintéticos con historia y estados preparados. |
| Nuevo | `scripts/demos/cu11-fixture.py` | Testigos SQL y fallos/carrera propios; lectura de PDF sin instalar dependencias. |
| Nuevo | `scripts/demos/cu11-extras.py` | Conjunta en tres entornos aislados con modos OK/PENDING/UNAVAILABLE. |
| Nuevo | `scripts/demos/Cu11ReceiptText.java` | Extracción UTF-8 de PDF con PDFBox ya disponible. |
| Nuevo | `frontend/playwright.cu11-presentation.config.ts` | Chrome visible1440×1000, principal/extras y reporte propio. |
| Nuevo | `frontend/e2e-sustentacion/cu11-helpers.ts` | Operaciones UI reales, aserciones, capturas protegidas y PDF. |
| Nuevo | `frontend/e2e-sustentacion/cu11-extras.spec.ts` | Nueve escenarios independientes, sin inventar pedido con pago PENDING. |
| Nuevo | `frontend/e2e-sustentacion/cu11-reporter.ts` | Pasos descriptivos y tiempos, sin valores reservados. |
