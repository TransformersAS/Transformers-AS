# Corrección de Backend CI and GHCR #234

Fecha: 14 de noviembre de 2026. Rama de la ejecución remota: `feature/rnf-seguridad-zap`. No se ejecutaron operaciones Git ni se cambió la rama local.

## Fallo y evidencia

[Workflow #234](https://github.com/TransformersAS/Transformers-AS/actions/runs/43624762251), revisión `a9a25945550ac7a35eb0f99e467591c79b43aa49`: Maven y quality checks aprobados; frontend E2E cancelado al alcanzar 20 minutos. Web Push, CU-08, CU-09 y Docker quedaron sin ejecutar. El [ZAP #3](https://github.com/TransformersAS/Transformers-AS/actions/runs/43624762204) de la misma revisión fue aprobado.

Se descargó el artefacto Playwright 14126720890 y se comprobó SHA-256 `dc5cc7baf311600852e13cae8515a1a53b0619091d41cfd9759b972cc3c55f0d`. Contiene 97 archivos error-context.md, sin HTML, capturas ni ZIP de trazas. No se interpreta la ausencia del HTML como ausencia de errores: la cancelación interrumpió la finalización del reporter. La configuración anterior capturaba trazas únicamente en el primer reintento, pero no configuraba reintentos.

Primeras fallas concretas:

- `access-validation.spec.ts:40`: `.welcome-strip` no aparece; espera 5 s por `Hola, person`.
- `access-validation.spec.ts`: las respuestas de cambio de contraseña fallan en el mismo inicio de sesión simulado, antes de evaluar la respuesta específica.
- Acceso a `Ver cuenta`: timeout de 30 s porque el botón no existe.
- `home.spec.ts:3`: el enlace `Marketplace Integral, inicio` no aparece. Esta prueba se reprodujo sola antes de cambiar la configuración y falló en 5,7 s.

El artefacto original, su resumen extraído y la reproducción están en `artifacts/ci234-investigation/`. El log remoto se conserva en `github-job-log.json`; las operaciones Git que aparecen allí pertenecen al checkout remoto histórico, no fueron ejecutadas durante esta investigación.

## Causa raíz demostrada

El workflow construye correctamente el frontend, pero Playwright arrancaba `npm start` / `ng serve` en lugar de servir ese build. La remediación habilitó SRI en Angular. El servidor de desarrollo basado en Vite transforma los módulos después de calcular la integridad. Chrome recibe bytes diferentes y los bloquea: `Failed to find a valid digest in the integrity attribute ... The resource has been blocked`.

Se capturaron doce errores de integridad (main, polyfills y módulos precargados) y una pantalla sin interfaz con el servidor anterior. Evidencia: `dev-server-browser.json` y `dev-server-blank.png`. La aplicación no arranca y los selectores agotan esperas en cascada. No se demostró un fallo de selectores, un problema de tiempos de Angular 20 ni de lógica de negocio. Tampoco se corrigió eliminando SRI o debilitando CSP.

## Corrección

`frontend/playwright.config.ts` reutiliza `e2e-security-server.cjs`, incorporado en la remediación anterior: sirve los archivos de `dist/browser` sin transformar los módulos, aplica la CSP de Nginx, genera nonce aleatorio por respuesta y no reenvía APIs a una base compartida. Cada suite sigue controlando sus respuestas simuladas. El servidor escucha únicamente en loopback, usa un puerto configurable y nunca reutiliza un proceso ajeno. Es necesario ejecutar `npm run build` antes de las pruebas; CI ya tenía esa etapa.

Se conservan los proyectos Chrome, Edge y mobile, todos los specs y sus expectativas. CI mantiene dos workers y 20 minutos. Las trazas se retienen ante el primer fallo y se agregan capturas sólo si falla una prueba. El reporter por defecto muestra progreso en consola y conserva HTML sin abrir un servidor interactivo. El workflow deja de reemplazar esa configuración con un reporter exclusivamente HTML. Las etapas posteriores y sus controles no cambian.

## Validación

| Verificación local | Resultado |
|---|---|
| Reproducción con servidor anterior | Home falla: interfaz ausente y errores SRI |
| Muestra home + access-validation | 13/13, 10,8 s |
| Regresión completa Chrome, 2 workers | 159/159, 103,26 s; sin omitidas, fallidas, flaky ni reintentos |
| Web Push | 4/4, 7,2 s |
| CU-08 con backend/MySQL reales | 1/1, 9,1 s |
| CU-09 con backend/MySQL/Mailpit reales | 1/1, 9,0 s |
| Herramientas de seguridad/reportes | 31/31 |
| SHA-384, CSP, nonce, DENY, nosniff | Correctos; 13 recursos verificados |
| Docker frontend/backend | Construidos correctamente por los scripts reales |
| Limpieza Compose | Ambos proyectos sin contenedores, redes o volúmenes restantes |

La comparación de hashes con el manifiesto del último ZAP local no detectó cambios en ninguno de sus archivos de producción/escáner: `production-source-comparison.json`. No se repitió Maven, ya aprobado remotamente; tampoco se repitió el DAST completo porque no cambió la superficie desplegada. Se verificaron nuevamente los controles en navegador y las 31 pruebas del tooling.

Los resultados, HTML de la regresión, contextos originales, capturas de reproducción, logs y resumen JSON se conservan en `artifacts/ci234-investigation/`. Las pruebas generales usan APIs simuladas y no sustituyen E2E reales ni ZAP. La regresión ejecutada corresponde a Chrome, como CI; Edge y mobile conservan su configuración pero no se ejecutaron en esta investigación. La corrección sólo cambia cómo se sirve el frontend de prueba; CSP, SRI, nonces, cabeceras Nginx, paquetes Angular, controles de backend y política ZAP no se modifican.

Comandos desde `frontend`:

```sh
npm run build
CI=1 PLAYWRIGHT_PORT=45983 npx playwright test e2e/home.spec.ts e2e/access-validation.spec.ts --project=chrome --workers=2
CI=1 PLAYWRIGHT_PORT=45983 npx playwright test --project=chrome
CI=1 PUSH_TEST_PORT=45985 npx playwright test --config=playwright.push.config.ts --project=chrome --reporter=list
```

Desde la raíz:

```sh
python3 -m unittest discover -s scripts/security/tests -v
CU08_E2E_PORT=15483 bash scripts/cu08-real-e2e.sh
CU08_E2E_PORT=15489 CU09_MAIL_PORT=18489 bash scripts/cu09-real-e2e.sh
node artifacts/ci234-investigation/verify-security.cjs
```

Los scripts reales crean proyectos Compose con bases desechables; no usan las bases ya existentes. Sus directorios de reportes anteriores se respaldaron dentro de `artifacts/ci234-investigation/before/`. El script auxiliar de evidencia comprueba los hashes SHA-384 de los 13 recursos con integridad, nonces diferentes entre respuestas, HTML no-store, DENY, nosniff, arranque real de Angular y rechazo de script sin nonce.

No se lanzó un nuevo workflow remoto ni se publicaron imágenes. El estado verde remoto de esta corrección requiere el commit/push manual y una ejecución posterior; las verificaciones locales no se presentan como un nuevo resultado de GitHub Actions.

## Archivos exactos para commit manual

- `.github/workflows/backend-ci.yml`
- `frontend/playwright.config.ts`
- `docs/seguridad/correccion-ci234-playwright.md`

Los artefactos de investigación quedan fuera de esta lista. No hay cambios en CU-12, negocio, mensajería, desempeño, k6, paquetes o política del escáner.
