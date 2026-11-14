# Remediación de seguridad Angular 20 — 14 de noviembre de 2026

Estado real: **Parcial**. Se corrigieron los hallazgos altos y medios del DAST y las dependencias de producción evaluadas; quedan vulnerabilidades en herramientas de desarrollo. No se certifica ausencia absoluta de vulnerabilidades.

## Objetivo, ambiente y herramientas

Eliminar la biblioteca Angular vulnerable y corregir configuraciones inseguras sin alterar funcionalidades. Se utilizó Docker Compose exclusivo y descartable, MySQL nuevo en tmpfs, backend Spring Boot y frontend Nginx reales, redes internas sin puertos publicados ni proveedores productivos. ZAP 2.17.0 en modo pasivo, npm audit, Angular CLI oficial, Playwright 1.63.0 y Chromium 153.0.8010.12. Node local 22.22.3. Los dos proyectos finales confirmaron limpieza sin contenedores, redes o volúmenes restantes.

## Dependencias y migración

| Paquete | Antes | Después |
|---|---|---|
| Angular core/common/forms/router/platform-browser/service-worker | 18.2.14 | 20.3.35 |
| compiler / compiler-cli | rama 18 (compiler como peer) | 20.3.35 explícitos |
| CLI / build-angular | 18.2.14 | 20.3.43 |
| TypeScript | 5.5.x | 5.9.3 |
| Zone.js | 0.14.x | 0.15.1 |
| Ionic | 8.8.19 | 8.8.19 |

Se verificaron los parches publicados y los peers; la resolución estricta terminó sin conflictos. Node admitido: ^20.19.0, ^22.12.0 o ^24.0.0. RxJS resuelto 7.8.2 y Capacitor 6.2.2 conservados. Compatibilidad oficial: https://angular.dev/reference/versions.

Se ejecutó la secuencia oficial 18 → 19.2.25 → 20 en una copia de migración. Se incorporaron los cambios de dependencias y configuración necesarios. No se copiaron 32 modificaciones redundantes de componentes (formato/eliminación de standalone:true); los componentes originales compilan y pasan las pruebas en Angular 20. Se conservaron Zone y la lógica de negocio. No se usó --force ni --legacy-peer-deps. Un guard de subprocess impidió las comprobaciones Git internas de ng update antes de ejecutarlas; no se ejecutaron operaciones Git ni se verificó/cambió la rama.

## Causa de la alerta alta y seis avisos

ZAP reconocía Angular 18.2.14 como biblioteca vulnerable. Que no se hayan localizado todos los patrones de explotación en la aplicación no elimina el defecto de la biblioteca. Angular 20.3.35 está por encima de los parches enumerados. Los rangos completos por paquete y rama están conservados en advisories.json; esta tabla muestra la rama objetivo 20.

| CVE / fuente oficial | Rango afectado Angular 20 | Primer parche 20 |
|---|---|---|
| [CVE-2026-54267](https://github.com/advisories/GHSA-rgjc-h3x7-9mwg) — Angular Client Hydration DOM Clobbering & Response-Cache Poisoning | `>= 20.0.0-next.0 < 20.3.25` | 20.3.25 |
| [CVE-2026-32635](https://github.com/advisories/GHSA-g93w-mfhg-p222) — XSS in i18n attribute bindings | `>= 20.0.0-next.0 < 20.3.18` | 20.3.18 |
| [CVE-2026-88057](https://github.com/advisories/GHSA-hh8m-fm6v-7cvg) — Sanitization bypass via directive host bindings on concrete host elements in @angular/core and @angular/compiler | `>= 20.0.0, < 20.3.28` | 20.3.28 |
| [CVE-2026-27970](https://github.com/advisories/GHSA-prjf-86w9-mfqv) — Cross-Site Scripting (XSS) in Angular i18n | `>= 20.0.0-next.0 <= 20.3.16` | 20.3.17 |
| [CVE-2026-50557](https://github.com/advisories/GHSA-f3m7-gqxr-g87x) — Template and Attribute Namespace Sanitization Bypass (XSS) | `>= 20.0.0-next.0 < 20.3.22` | 20.3.22 |
| [CVE-2026-52725](https://github.com/advisories/GHSA-692r-grfm-v8x7) — Dynamic Component Instantiation Mounting Protection Bypass (XSS) | `>= 20.0.0-next.0 < 20.3.22` | 20.3.22 |

## Cambios de configuración

Nginx agrega CSP con nonce aleatorio por respuesta, frame-ancestors y X-Frame-Options DENY, nosniff, Referrer-Policy y Permissions-Policy; oculta la versión del servidor. Reemplaza las cabeceras duplicadas del upstream por una política uniforme también en errores. Angular genera SRI y propaga ngCspNonce. No se permiten unsafe-inline ni unsafe-eval. Las fuentes antes externas se alojan localmente con sus licencias OFL; se conserva Unsplash como origen explícito de imágenes.

El HTML usa Cache-Control no-store y no se precarga ni se sirve desde el service worker para evitar reutilizar nonces o invalidar hashes del manifiesto. Los recursos estáticos siguen cacheados; el worker quedó activo y en estado NORMAL. **El arranque del app shell sin conexión requiere red con esta configuración.** Usuarios con un worker antiguo deben recibir la actualización y recargar; validar el despliegue y la política de caché de cualquier CDN antes de publicar.

El único ajuste de presentación de negocio fue trasladar el style inline del aviso de sesión a una clase SCSS: dos pruebas existentes demostraron que CSP lo bloqueaba. No cambió su comportamiento. La comparación SHA-256 con el inicio confirma que todos los demás archivos protegidos de app, mensajería, CU-12 y desempeño permanecen iguales.

## Resultado antes y después

| Hallazgo ZAP | Antes | Después | Evaluación |
|---|---:|---:|---|
| Biblioteca JavaScript vulnerable (10003) | 1 alta | 0 | Dependencias corregidas |
| Protección clickjacking (10020) | 1 media | 0 | Cabeceras corregidas |
| CSP ausente (10038) | 1 media | 0 | Política aplicada |
| SRI ausente (90003) | 1 media | 0 | Integridad aplicada y comprobada |
| Versión servidor (10036) | 24 bajas | 0 | Banner corregido |
| nosniff ausente (10021) | 14 bajas | 0 | Cabecera uniforme |
| Timestamp (10096) | 5 bajas | 5 bajas | Identificadores públicos de imágenes |
| Aplicación SPA (10109) | 1 informativa | 1 informativa | Clasificación de arquitectura |
| Total | 48 | 6 | 42 instancias eliminadas |

Las cinco coincidencias 1542272604, 1551028719, 1553062407, 1542291026 y 1556228578 se trazaron a URLs públicas photo-* de Unsplash en catalogo.service.ts (líneas 127,131,135,179,198). No son fechas de cuentas, sesiones ni transacciones. Se consideran falsos positivos respecto de divulgación de fechas sensibles, con evidencia exacta en timestamp-review.json; las alertas originales permanecen intactas. La clasificación SPA tampoco constituye por sí misma una vulnerabilidad.

npm audit --omit=dev: 6 entradas afectadas (4 altas, 2 moderadas) → **0**. Auditoría completa: **24 entradas afectadas de desarrollo: 1 crítica, 1 alta y 22 moderadas**; no equivalen a 24 CVE distintas. Incluyen tar crítico, cadena de @capacitor/cli alta y cadenas de CLI/build Angular. El detalle, rangos, avisos y fixes sugeridos están en audit-all.json. No se ejecutó npm audit fix --force.

Propuesta separada pendiente: evaluar actualización coordinada de Capacitor CLI/core y herramientas Angular, reproducir los avisos de tar, minimatch, sockjs, uuid, webpack-dev-server y cadenas npm/sigstore; verificar compatibilidad Ionic/nativa, builds y E2E. Los fixes mayores sugeridos por npm (incluso downgrade de CLI) no se adoptan automáticamente. No declarar remediación completa hasta corregir y volver a auditar esas cadenas.

## Pruebas y pipeline

- npm ci --strict-peer-deps, npm ls y npm run build: correctos; frontend y backend Docker construidos.
- Suite frontend existente sobre build de producción con CSP y SRI: **159 aprobadas**, cero omitidas, fallidas o inestables. APIs simuladas según los specs existentes; no equivale a cobertura real de backend.
- Seguridad y reporte: **31 pruebas aprobadas**.
- Navegador contra frontend/backend/MySQL reales: login, roles, escrituras con CSRF, catálogo, carrito, favoritos, cálculo de checkout, conversación/respuesta y notificación visibles tras operaciones asincrónicas. CSP sin infracciones durante esos flujos; script sin nonce bloqueado, SRI verificado, nonce nuevo y service worker activo/NORMAL.
- npm test no es ejecutable como suite unitaria: el proyecto ya carecía de target test en angular.json. Se conserva esa limitación; no se informa como aprobado.
- Runner ZAP original: exit 0, cobertura aprobada, warnings_for_review, cero excepciones, cero altas y medias. Política y workflow sin cambios. **GitHub Actions remoto no fue ejecutado**.

No se ejecutaron pagos reales, push externo, pruebas nativas ni recertificación de desempeño. DAST pasivo con tráfico anónimo y COMPRADOR no cubre toda la aplicación ni sustituye pruebas activas, SCA continua o revisión manual de todos los roles.

## Evidencia y reproducción

Desde la raíz del repositorio (Docker local y Node compatible):

```sh
cd frontend
npm ci --strict-peer-deps
npm run build
npx playwright test --config=playwright.security.config.ts
npm audit --omit=dev
cd ..
python3 -m unittest discover -s scripts/security/tests -v
python3 scripts/security/validate-browser.py
python3 scripts/security/run.py
```

Los validadores crean proyectos nuevos y limpian únicamente sus recursos. El pago del navegador se detiene en el cálculo de checkout, sin invocar proveedor externo.

- Informe ejecutivo HTML: ../../artifacts/security-remediation/angular20-caa13b0b78/remediation-executive.html
- Resumen JSON y auditorías: ../../artifacts/security-remediation/angular20-caa13b0b78/
- ZAP anterior sin alterar: ../../artifacts/security-zap/rnf-zap-271abb763e66/zap-report.json
- ZAP nuevo y evidencia de cobertura/limpieza: ../../artifacts/security-zap/rnf-zap-732807eef10d/
- Navegador real final y limpieza: ../../artifacts/security-remediation/browser-rnf-zap-bdbd515d59d5/

Regenerar el informe comparativo:

```sh
python3 scripts/security/remediation-report.py --before artifacts/security-zap/rnf-zap-271abb763e66/zap-report.json --after artifacts/security-zap/rnf-zap-732807eef10d/zap-report.json --evidence artifacts/security-remediation/angular20-caa13b0b78 --ui artifacts/security-remediation/browser-rnf-zap-bdbd515d59d5/security-ui.json
```

## Archivos exactos para commit manual

No se ejecutó commit. Esta lista corresponde únicamente a la remediación; no incluye node_modules, dist, artefactos de ejecución ni cambios previos ajenos. Conservar/copiar los informes de evidencia por el mecanismo académico elegido.

- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/angular.json`
- `frontend/ngsw-config.json`
- `frontend/nginx.conf`
- `frontend/nginx.compose.conf`
- `frontend/src/index.html`
- `frontend/src/global.scss`
- `frontend/src/app/app.component.html`
- `frontend/src/app/app.component.scss`
- `frontend/playwright.security.config.ts`
- `frontend/e2e-security-server.cjs`
- `scripts/security/browser-check.cjs`
- `scripts/security/validate-browser.py`
- `scripts/security/remediation-report.py`
- `scripts/security/tests/test_remediation_report.py`
- `docs/seguridad/rnf-owasp-zap.md`
- `docs/seguridad/remediacion-angular20.md`
- `frontend/src/assets/fonts/24e94e12a88d31c6.ttf`
- `frontend/src/assets/fonts/25030a7ef0d694d3.ttf`
- `frontend/src/assets/fonts/284891c74c258cff.ttf`
- `frontend/src/assets/fonts/2a8e7f713ee79fbc.ttf`
- `frontend/src/assets/fonts/376427b5322148d8.ttf`
- `frontend/src/assets/fonts/3b208e154d0389c8.ttf`
- `frontend/src/assets/fonts/4f8e04394b857075.ttf`
- `frontend/src/assets/fonts/50fe1039eb3ff208.ttf`
- `frontend/src/assets/fonts/6091e0bd6640a4fd.ttf`
- `frontend/src/assets/fonts/82f40cfcacfbf3de.ttf`
- `frontend/src/assets/fonts/913ee3631949ee1b.ttf`
- `frontend/src/assets/fonts/a38e15482db29581.ttf`
- `frontend/src/assets/fonts/a3c8bba596ed0306.ttf`
- `frontend/src/assets/fonts/b0ae3e89a7d3ef2b.ttf`
- `frontend/src/assets/fonts/b56db40c26850f83.ttf`
- `frontend/src/assets/fonts/b5ef8100ac2aa8c2.ttf`
- `frontend/src/assets/fonts/bricolagegrotesque-OFL.txt`
- `frontend/src/assets/fonts/ccb2199b97c471a7.ttf`
- `frontend/src/assets/fonts/dmsans-OFL.txt`
- `frontend/src/assets/fonts/e1b454f5469f3d0d.ttf`
- `frontend/src/assets/fonts/fonts.css`

## Seguimiento de dependencias — 2026-11-14

La auditoría posterior conservadora redujo las 24 entradas de desarrollo a dos: tar crítica y @capacitor/cli alta; producción sigue en cero. El cierre definitivo no se alcanzó. Se preservan los resultados anteriores como evidencia histórica. Véanse [rangos, cambios transitivos, pruebas, limitaciones y alternativa Capacitor 7 evaluada](auditoria-dependencias-desarrollo.md).
