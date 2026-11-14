# Auditoría de dependencias de desarrollo — 2026-11-14

**Estado: parcial; el cierre definitivo de Seguridad no está alcanzado.** La corrección aplicada elimina las 22 entradas moderadas. Permanecen dos entradas afectadas: tar crítica y @capacitor/cli alta. No se cambiaron los umbrales ni se ocultaron alertas. npm audit completo sigue terminando con exit 1; producción termina con exit 0.

## Resultado inicial y final

| Severidad | Inicial | Final aplicado |
|---|---:|---:|
| Crítica | 1 | 1 |
| Alta | 1 | 1 |
| Moderada | 22 | 0 |
| Baja / informativa | 0 | 0 |
| Total | 24 | 2 |
| Producción, todas las severidades | 0 | 0 |

Son entradas de paquetes afectadas, incluidas las propagadas por npm; no equivalen a 24 CVE distintas. Se conservan 15 avisos únicos: 12 de tar y tres de glob, minimatch y uuid. La ausencia de patrones de explotación no se utiliza como sustituto de la corrección.

## Corrección aplicada

Dependencias directas conservadas: Angular 20.3.35, CLI/build-angular 20.3.43, Ionic 8.8.19, TypeScript 5.9.3, Zone.js 0.15.1 y Capacitor core/cli 6.2.2 resueltos. No se modificó la lógica de negocio ni los requisitos Node del proyecto.

Se agregaron overrides reproducibles en package.json para glob 14.0.6, minimatch 11.0.8 y, únicamente dentro de sockjs, uuid 11.1.1. Son versiones publicadas corregidas; las APIs utilizadas por los consumidores se comprobaron con pruebas de contratos, instalación estricta y npm ls --all. No se usaron --force, --legacy-peer-deps, audit-level, omisiones de auditoría ni edición de node_modules. Los overrides cruzan versiones mayores transitivas: no constituyen soporte oficial de esas combinaciones por Angular y se deben revisar cuando los consumidores publiquen rangos corregidos.

| Paquete transitivo | Antes | Después |
|---|---|---|
| glob | 13.0.7 y copia 9.3.5 | 14.0.6 |
| minimatch | 10.2.6 y copia 8.0.7 | 11.0.8 |
| uuid de SockJS | 8.3.2 | 11.1.1 |
| path-scurry | 1.11.1 y copia 2.0.2 | 3.0.0 |
| brace-expansion / balanced-match | 2.1.8 / 1.0.2, más copias | 5.0.13 / 4.0.4 |

El lock conserva todos los paquetes directos y las herramientas. Las rutas retiradas corresponden a copias transitivas deduplicadas; el detalle de ocho rutas actualizadas/agregadas y ocho retiradas está en lock-changes.json. Se añadió @isaacs/nonrecursive-glob-match 1.1.0 como dependencia de minimatch corregido.

Compatibilidad Node: glob 14 admite 18, 20 y >=22; minimatch 11 requiere 20 o >=22. El proyecto ya exige ^20.19.0, ^22.12.0 o ^24.0.0. Se comprobó en Node 24.8.0/npm 11.6.0 y Node 22/Linux. Se mantuvieron las versiones de Angular, Ionic, TypeScript, Zone y Capacitor.

## Raíces y trazabilidad de las 24 entradas

Las raíces son Capacitor CLI (tar y rimraf/glob), Angular CLI (pacote → caché, paquetes npm y sigstore/TUF → glob/minimatch) y build-angular (build-webpack/webpack-dev-server → sockjs → uuid). Los árboles completos, npm explain y rangos originales se conservan en la evidencia.

| Entrada | Severidad inicial | Dependencias raíz | Resultado |
|---|---|---|---|
| @angular-devkit/build-angular | moderate | @angular-devkit/build-angular | resuelto |
| @angular-devkit/build-webpack | moderate | @angular-devkit/build-angular | resuelto |
| @angular/cli | moderate | @angular/cli | resuelto |
| @capacitor/cli | high | @capacitor/cli | pendiente |
| @npmcli/package-json | moderate | @angular/cli | resuelto |
| @npmcli/run-script | moderate | @angular/cli | resuelto |
| @sigstore/sign | moderate | @angular/cli | resuelto |
| @sigstore/tuf | moderate | @angular/cli | resuelto |
| @tufjs/models | moderate | @angular/cli | resuelto |
| cacache | moderate | @angular/cli | resuelto |
| glob | moderate | @capacitor/cli, @angular/cli | resuelto |
| ignore-walk | moderate | @angular/cli | resuelto |
| make-fetch-happen | moderate | @angular/cli | resuelto |
| minimatch | moderate | @capacitor/cli, @angular/cli | resuelto |
| npm-packlist | moderate | @angular/cli | resuelto |
| npm-registry-fetch | moderate | @angular/cli | resuelto |
| pacote | moderate | @angular/cli | resuelto |
| rimraf | moderate | @capacitor/cli | resuelto |
| sigstore | moderate | @angular/cli | resuelto |
| sockjs | moderate | @angular-devkit/build-angular | resuelto |
| tar | critical | @capacitor/cli | pendiente |
| tuf-js | moderate | @angular/cli | resuelto |
| uuid | moderate | @angular-devkit/build-angular | resuelto |
| webpack-dev-server | moderate | @angular-devkit/build-angular | resuelto |

## Avisos y rangos

La columna de rango reproduce npm audit inicial. Cada GHSA completo, con los rangos y versiones corregidas por rama, se conserva como JSON.

| Paquete | CVE / aviso oficial | Severidad | Rango afectado | Estado |
|---|---|---|---|---|
| glob | [CVE-2026-118773](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) | moderate | `>=9.0.0 <=14.0.1` | Corregido |
| minimatch | [CVE-2026-118764](https://github.com/advisories/GHSA-g3hv-75wg-m249) | moderate | `>=7.0.0 <11.0.1` | Corregido |
| tar | [CVE-2026-24842](https://github.com/advisories/GHSA-34x7-hfp2-rc4v) | high | `<7.5.7` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-23745](https://github.com/advisories/GHSA-8qq5-rm4j-mr97) | high | `<=7.5.2` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-26960](https://github.com/advisories/GHSA-83g3-92jg-28cx) | high | `<7.5.8` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-29786](https://github.com/advisories/GHSA-qffp-2rhf-9h96) | high | `<=7.5.9` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-31802](https://github.com/advisories/GHSA-9ppj-qmqm-q256) | high | `<=7.5.10` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-23950](https://github.com/advisories/GHSA-r6q2-hw4h-h46w) | high | `<=7.5.3` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-53655](https://github.com/advisories/GHSA-vmf3-w455-68vh) | moderate | `<=7.5.15` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-59871](https://github.com/advisories/GHSA-w8wr-v893-vjvp) | moderate | `<=7.5.17` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-59873](https://github.com/advisories/GHSA-23hp-3jrh-7fpw) | critical | `<=7.5.18` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-59874](https://github.com/advisories/GHSA-8x88-c5mf-7j5w) | high | `<=7.5.17` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-59875](https://github.com/advisories/GHSA-gvwx-54wh-qm9j) | moderate | `<=7.5.16` | Pendiente: tar 6.2.1 |
| tar | [CVE-2026-73566](https://github.com/advisories/GHSA-r292-9mhp-454m) | high | `<=7.5.20` | Pendiente: tar 6.2.1 |
| uuid | [CVE-2026-41907](https://github.com/advisories/GHSA-w5hq-g745-h8pq) | moderate | `<11.1.1` | Corregido |

## Bloqueo de tar y Capacitor

Capacitor CLI 6.2.2 depende de tar ^6.1.11 y resuelve 6.2.1. El parche publicado más reciente de CLI 6, 6.2.3, conserva el mismo rango; actualizar sólo ese parche no remedia tar. Angular CLI y node-gyp ya resuelven su propia copia corregida tar 7.5.23.

No se fuerza tar 7 sobre Capacitor 6: el helper de extracción de CLI 6 usa una importación default incompatible. Se reprodujo en una VM descartable con el código de extracción instalado y tar 7.5.23: `Cannot read properties of undefined (reading 'extract')`. No se tocó el node_modules de trabajo para esta reproducción. Evidencia: capacitor6-tar7-incompatibility.json. Está documentado por el proyecto en https://github.com/ionic-team/capacitor/issues/8573. No se elimina Capacitor ni se mantiene una bifurcación local sin soporte.

## Alternativa evaluada; no aplicada

Una copia aislada con **@capacitor/core y @capacitor/cli 7.6.10**, junto con los mismos tres overrides, obtuvo **cero entradas en npm audit completo**, instalación estricta sin conflictos, build, 13 pruebas focalizadas, las 159 Playwright y las cinco pruebas de contratos (incluida extracción real de un tar). Angular e Ionic permanecieron sin cambios. Los archivos package.json/package-lock.json de esa propuesta están en patched-candidate/ dentro de la evidencia.

No se trasladó esa actualización mayor al proyecto. Aquí no se encontraron plataformas Android/iOS ni plugins nativos, pero no está confirmado si el equipo los mantiene fuera del repositorio. La guía oficial de Capacitor 7 exige Node 20+, Xcode 16+, iOS 14+, Android Studio Ladybug/JDK 21, SDK mínimo 23 y compile/target 35, además de migrar plugins: https://capacitorjs.com/docs/updating/7-0. No se certificaron compilaciones Android/iOS. Esta candidata es una alternativa web verificada, no una migración nativa autorizada ni completa.

Para completar el cierre, confirmar el alcance nativo con el equipo; si existe, migrar y comprobar esos proyectos/plugins antes de adoptar Capacitor 7. Si el alcance es exclusivamente web/PWA, aplicar la candidata revisada, repetir instalación/build/auditorías/pruebas y conservar la evidencia final. No se recomienda Angular 21/22, Capacitor 8 ni un downgrade de CLI para evitar estas entradas.

## Pruebas y límites

- npm ci --strict-peer-deps: aprobado. npm ls --all --json: exit 0.
- npm run build y generador Angular en dry-run: aprobados.
- Playwright focalizado: 13 aprobadas; regresión Chrome con 2 workers: **159 aprobadas en 70,16 s**, sin omitidas, fallidas ni flaky.
- Web Push: primer recorrido **3/4**; falló la comprobación visual de notificación del worker (sin excepción de navegador asociada). La prueba focalizada pasó **1/1** y la repetición completa pasó **4/4** sin cambios. El lock original pasó **4/4**. Los builds original y corregido tienen recursos idénticos byte a byte; únicamente cambia el timestamp de ngsw.json. La causa de la incidencia intermitente no se confirmó y no se considera remediada por el reintento. Se conservan la traza y los tres resultados en push-results/, push-focused.log, push-repeat.log y baseline-push.log.
- Nuevas pruebas de contratos: **5/5** en Node 24 y **5/5** en Node 22/Linux. Verifican extracción tar de Capacitor, escritura/listado/lectura cacache, filtros ignore-walk, límites de delegación TUF y transporte SockJS/WebSocket real con UUID.
- Tooling de Seguridad/reportes: **31/31**. Navegador: SHA-384 válido en 13 recursos, nonce nuevo por respuesta, HTML no-store, DENY/nosniff y bloqueo de script sin nonce.
- Suite unitaria Node completa: **29 aprobadas y 54 fallidas (83)**. Con el lock original, en otra instalación aislada: **24 aprobadas y las mismas 54 fallidas (78)**. Las cinco aprobadas adicionales son las pruebas nuevas. Se comprobó igualdad de los nombres de los 54 fallos. Los fixtures anteriores omiten colaboradores de mensajería introducidos en componentes; no se alteraron esas pruebas, sus expectativas ni código ajeno para ocultar el problema. Esta suite no se informa como verde.
- npm test mantiene el problema previo: no existe target ng test configurado. Se ejecutaron los tests Node presentes y Playwright por sus comandos reales.

No se ejecutó un nuevo workflow remoto ni un nuevo DAST completo. La política OWASP, CSP, SRI, nonces, cabeceras, pruebas existentes, scripts de desempeño, CU-06 y CU-12 quedaron sin modificaciones. No se ejecutó Git ni se leyó/cambió main. Las pruebas de contratos usan temporales propios y loopback; el contenedor Node 22 fue de sólo lectura, sin red y eliminado al finalizar. No se operaron entornos compartidos.

## Evidencia reproducible

Directorio: `artifacts/dependency-security/20261114T193710Z`.

```sh
cd frontend
npm ci --strict-peer-deps
npm run build
node --test tests/dependency-security.test.cjs
node --test tests/*.test.cjs
CI=1 PLAYWRIGHT_PORT=45988 npx playwright test --project=chrome
CI=1 PUSH_TEST_PORT=45989 npx playwright test --config=playwright.push.config.ts --project=chrome --reporter=list
npm audit --json
npm audit --omit=dev --json
cd ..
python3 -m unittest discover -s scripts/security/tests -v
```

npm audit completo debe seguir devolviendo exit 1 mientras permanezcan las dos entradas. No se debe tratar ese resultado como aprobado.

## Archivos exactos para commit manual

- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/tests/dependency-security.test.cjs`
- `docs/seguridad/auditoria-dependencias-desarrollo.md`
- `docs/seguridad/remediacion-angular20.md` (anexo; preserva el resultado histórico)

No incluir node_modules, dist ni las copias de auditoría/migración como código fuente. Conservar los artefactos por el mecanismo de evidencia académico.
