# Validación final conjunta — CU-07 a CU-11

Worktree exclusivo: `/Users/sofiamantilla/Documents/GitHub/Transformers-AS-demos-cu07-cu11`. Rama `feature/demos-cu07-cu11` comprobada leyendo metadatos, sin Git. Evidencias propias: `artifacts/final-demos-validation/validation-2bcd72f30140/`. [Guía de sustentación](guia-final-demos-cu07-cu11.md).

## Alcance y criterio de cierre

Se ejecutan las cinco principales, en orden CU07→CU11, y luego las cinco suites completas de complementos, también en ese orden. No se ejecutan dos comandos de demo a la vez. Las acciones, escenarios y aserciones existentes se conservan íntegros; no se repite desde cero la auditoría de RF ni se añaden funcionalidades.

El resultado confirma preparación técnica de las demostraciones bajo las limitaciones de sus auditorías. No equivale a cobertura funcional completa ni a tener todas las suites históricas del repositorio en verde. No se ejecutó Git, no se creó commit/merge y no se inspeccionó el estado staged/tracked frente a main.

## Resultados efectivos de las demostraciones

| Comando | Casos aprobados | Recorridos Playwright | Total con arranque/limpieza |
|---|---:|---:|---:|
|`npm --prefix frontend run demo:cu07`|1|43.320s|94.957s|
|`npm --prefix frontend run demo:cu08`|1|51.683s|92.163s|
|`npm --prefix frontend run demo:cu09`|1|36.164s|76.750s|
|`npm --prefix frontend run demo:cu10`|1|74.283s|114.319s|
|`npm --prefix frontend run demo:cu11`|1|45.379s|85.742s|
|`npm --prefix frontend run demo:cu07:extras`|5|245.144s|286.081s|
|`npm --prefix frontend run demo:cu08:extras`|7|331.033s|372.462s|
|`npm --prefix frontend run demo:cu09:extras`|6|272.629s|313.689s|
|`npm --prefix frontend run demo:cu10:extras`|9|342.418s|383.776s|
|`npm --prefix frontend run demo:cu11:extras`|9|251.724s|373.767s|

**41/41 aprobados: 5 principales y 36 complementos.** Diez comandos secuenciales, 12 proyectos aislados; CU11 complementos usa tres proyectos sucesivos.

Los tiempos de Playwright son recorridos; los totales incluyen compilación/caché, arranque, testigos y limpieza. CU11 extras agrega tres proyectos nuevos sucesivos. Cada caso se conserva con título, estado y duración en JSON y HTML. Los errores deliberados de SMTP, infraestructura, contraseña o permisos forman parte de escenarios negativos; no se clasifican como regresión cuando cumplen sus aserciones.

## Instalación, compilación y auditorías npm

La instalación/build se ejecutaron en una copia temporal de las mismas fuentes y lockfile, dentro del directorio propio de evidencia. No se sobrescribieron node_modules, dist, reportes previos ni dependencias del workspace. Se retiró la copia después de extraer resultados y versiones.

| Comprobación | Resultado | Duración |
|---|---|---:|
|`npm ci`|Exit 0; 1018 paquetes instalados; sin cambiar lockfile|7,708s|
|`npm run build`|Exit 0, compilación de producción correcta|15,093s|
|`npm audit --json`|Exit 0; 0 vulnerabilidades en todas las severidades|0,808s|
|`npm audit --omit=dev --json`|Exit 0; 0 vulnerabilidades de producción|0,524s|

Referencia anterior: [cierre autorizado de dependencias](../seguridad/auditoria-dependencias-desarrollo.md),14/11/2026, completo 0/producción 0. Resultado actual 0/0: no hay incremento ni hallazgos npm nuevos. No se ejecutó audit fix, no se alteraron umbrales/exclusiones ni se actualizaron dependencias.

| Paquete | Versión autorizada/instalada |
|---|---|
|Angular runtime/compiler|20.3.35|
|Angular CLI/build-angular|20.3.43|
|Ionic Angular|8.8.19|
|Capacitor core/cli|7.6.10|

Las versiones leídas del workspace coinciden con la instalación limpia. Dependencies/devDependencies/overrides/engines coinciden con el package.json anterior a la auditoría CU07. Entorno: Node 24.8.0, npm 11.6.0, Python 3.14.5, Java 24.0.1, Docker 29.8.0 y Chrome 157.0.8103.51. El backend conserva Java 21 en su configuración y contenedores.

## Integridad y protecciones

- Comparación con snapshots anteriores: 1336 archivos CU07, 1386 CU08, 1458 CU09, 1476 CU10 y 1482 CU11. Son conjuntos superpuestos, no se suman como archivos únicos. Sólo difieren los scripts compartidos autorizados y principales auditadas; no hay cambios inesperados ni archivos faltantes.
- Frente al cierre de cada auditoría, sus archivos propios permanecen iguales. CU07–CU10 sólo registran cambios posteriores de run.py/package.json por las siguientes ampliaciones; CU11 no registra cambios posteriores. Esto justifica repetir las cinco suites completas de complementos.
- Código de negocio, controladores, migraciones, RNF de Seguridad/Desempeño, k6, ZAP, workflows, lockfile y corrección de concurrencia CU10 conservan sus hashes. No se repiten ni se modifican las mediciones RNF020.
- Las comprobaciones HTTP en vivo inspeccionan CSP, nonce concordante con HTML e integridad de scripts/CSS del HTML, y verifican 401 anónimo y 403 al login sin CSRF. Usan un contexto HTTP anónimo independiente en el ambiente propio; no registran cookies ni valores de nonce/token. No sustituyen una campaña DAST.

Comprobaciones HTTP positivas en 9/10 comandos, incluyendo al menos una ejecución de cada CU. CU07 principal terminó antes del sondeo; su integridad se comprueba por hashes y su suite complementaria sí tiene verificación HTTP positiva.

Los 12 proyectos terminaron con cleanup aprobado. Una consulta final independiente por etiqueta de proyecto confirmó cero contenedores, redes y volúmenes restantes. No se ejecutó limpieza global ni se intervinieron servicios externos a estos proyectos.

## Limitaciones conservadas y clasificación de fallos

| Tipo | Hallazgo que permanece |
|---|---|
|Brecha formal CU07|Asociación de COMPRADOR para correo existente exige autenticación y comienza con 409 en alta anónima; parcialmente alineada con el flujo formal.|
|Diferencias CU08|Restricciones de cuenta/rol, neutralidad ante error SMTP y ausencia de cambio universal de rol tras acceso prohibido, según auditoría.|
|Brecha funcional CU09|RF034 parcial: preferencias persistidas, sin emisor promocional que las aplique a envíos.|
|Fixture antiguo CU10|Cuatro pruebas unitarias del componente de notificaciones fallaron por falta de MensajeriaService en su fixture; no se reejecutan ni se modifican aquí.|
|Límite visual CU11|Visor PDF integrado con error en Chrome; descarga/contenido reales validados en detail, sin acreditar render del iframe.|
|Brecha de contrato CU11|Paso 18/A9 de pedido con pago PENDING no demostrable: ese estado no existe en OrderPaymentStatus. Checkout cerrado previo es otro flujo.|
|Fixture antiguo CU11|Suite unitaria Mis pedidos:4/43 aprobadas, 39 fallidas por ContactarVendedorComponent no sustituido; resultado anterior conservado, sin remediarlo.|
|Simulación explícita|Pagos/reembolsos/avisos externos usan adaptadores de prueba; ninguna confirmación se presenta como acreditación bancaria real. Fixtures logísticos no equivalen a ejecutar otros CU.|

Estos hallazgos no se convierten en tareas nuevas de desarrollo. Se distinguen de las regresiones efectivas de las demostraciones registradas en esta corrida. Las pruebas backend/frontend históricas citadas en las auditorías mantienen su fecha y alcance; no se presentan como nuevas ejecuciones de esta validación final.

**Regresiones nuevas detectadas: 0.** Las cinco demostraciones y sus 36 complementos están listas para sustentación e integración manual con las limitaciones anteriores expresamente conservadas. Esto no acredita corregir las brechas formales ni los fixtures unitarios históricos.

## Archivos y preparación de la integración manual

En esta validación sólo se crean:

- `docs/ejecucion/guia-final-demos-cu07-cu11.md`
- `docs/ejecucion/validacion-final-demos-cu07-cu11.md`

El snapshot inicial de esta validación contiene 1514 archivos previos: todos conservan su hash. **Archivos previos modificados en este cierre:ninguno.** No se cambiaron fuentes, aserciones, escenarios, dependencias ni configuración. Los nuevos artefactos de evidencia se enumeran separadamente bajo el directorio local propio.

El inventario consolidado proviene de los cinco manifiestos de auditoría: 7 archivos previos modificados y 40 creados; con estos dos documentos se listan 42 creados. Se separa la infraestructura común que ya existía antes de auditar. Esta clasificación describe las intervenciones documentadas, no sustituye un diff Git contra main.

**Archivos previos modificados en las cinco auditorías (7):**

- `frontend/e2e-sustentacion/cu07.spec.ts`
- `frontend/e2e-sustentacion/cu08.spec.ts`
- `frontend/e2e-sustentacion/cu09.spec.ts`
- `frontend/e2e-sustentacion/cu10.spec.ts`
- `frontend/e2e-sustentacion/cu11.spec.ts`
- `frontend/package.json`
- `scripts/demos/run.py`

**Archivos creados en las auditorías y este cierre (42):**

- `compose.cu08-presentation.yaml`
- `compose.cu10-presentation.yaml`
- `compose.cu11-presentation.yaml`
- `docs/ejecucion/auditoria-demo-cu07.md`
- `docs/ejecucion/auditoria-demo-cu08.md`
- `docs/ejecucion/auditoria-demo-cu09.md`
- `docs/ejecucion/auditoria-demo-cu10.md`
- `docs/ejecucion/auditoria-demo-cu11.md`
- `docs/ejecucion/guia-final-demos-cu07-cu11.md`
- `docs/ejecucion/validacion-final-demos-cu07-cu11.md`
- `frontend/e2e-sustentacion/cu07-extras.spec.ts`
- `frontend/e2e-sustentacion/cu07-helpers.ts`
- `frontend/e2e-sustentacion/cu07-reporter.ts`
- `frontend/e2e-sustentacion/cu08-extras.spec.ts`
- `frontend/e2e-sustentacion/cu08-helpers.ts`
- `frontend/e2e-sustentacion/cu08-reporter.ts`
- `frontend/e2e-sustentacion/cu09-extras.spec.ts`
- `frontend/e2e-sustentacion/cu09-helpers.ts`
- `frontend/e2e-sustentacion/cu09-reporter.ts`
- `frontend/e2e-sustentacion/cu10-extras.spec.ts`
- `frontend/e2e-sustentacion/cu10-helpers.ts`
- `frontend/e2e-sustentacion/cu10-reporter.ts`
- `frontend/e2e-sustentacion/cu11-extras.spec.ts`
- `frontend/e2e-sustentacion/cu11-helpers.ts`
- `frontend/e2e-sustentacion/cu11-reporter.ts`
- `frontend/playwright.cu07-presentation.config.ts`
- `frontend/playwright.cu08-presentation.config.ts`
- `frontend/playwright.cu09-presentation.config.ts`
- `frontend/playwright.cu10-presentation.config.ts`
- `frontend/playwright.cu11-presentation.config.ts`
- `scripts/demos/Cu11ReceiptText.java`
- `scripts/demos/cu07-fixture.py`
- `scripts/demos/cu07-seed.sql`
- `scripts/demos/cu08-fixture.py`
- `scripts/demos/cu08-seed.sql`
- `scripts/demos/cu09-fixture.py`
- `scripts/demos/cu09-seed.sql`
- `scripts/demos/cu10-fixture.py`
- `scripts/demos/cu10-seed.sql`
- `scripts/demos/cu11-extras.py`
- `scripts/demos/cu11-fixture.py`
- `scripts/demos/cu11-seed.sql`

**Infraestructura preexistente, sin cambios en este cierre:**

- `compose.sustentacion.yaml`
- `frontend/playwright.sustentacion.config.ts`
- `frontend/e2e-sustentacion/helpers.ts`
- `frontend/e2e-sustentacion/reporter.ts`
- `scripts/demos/seed.sql`
- `frontend/e2e-demo/account-order-flows.ts`


No incluir en Git evidencias locales ni artefactos regenerables: `artifacts/**`, capturas, PDFs sintéticos, logs/HTML/JSON de ejecución, `frontend/node_modules/**`, `frontend/dist/**`, `frontend/.angular/**`, `frontend/test-results*/**`, `frontend/playwright-report*/**`, logs de launcher o copias temporales de instalación. Las reglas existentes de .gitignore se conservan; no se comprobó si algún archivo generado estaba previamente tracked.

## Evidencias

[Índice HTML local](../../artifacts/final-demos-validation/validation-2bcd72f30140/index.html) · [Índice detallado](../../artifacts/final-demos-validation/validation-2bcd72f30140/validation.md) · [Resultados JSON](../../artifacts/final-demos-validation/validation-2bcd72f30140/results.json) · [Inventario consolidado](../../artifacts/final-demos-validation/validation-2bcd72f30140/integration-inventory.json).

Se conservan los diez logs de comandos, informes/capturas/SQL/cleanup de cada proyecto, agregación CU11, instalación/build, ambas auditorías npm, versiones instaladas, comparación histórica, impacto de cambios compartidos, verificaciones HTTP y revisión visual. No se eliminó ninguna evidencia previa. Las demostraciones siguen ejecutándose desde la raíz del worktree.
