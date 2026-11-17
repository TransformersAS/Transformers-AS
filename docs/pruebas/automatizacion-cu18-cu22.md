# Automatización de los casos de uso CU-18 a CU-22

Documento único de la automatización de los casos de uso CU-18 (Mi tienda), CU-19 (Devoluciones), CU-20 (Reportes
de contenido), CU-21 (Moderación) y CU-22 (Restricciones de cuentas): con qué se hizo, cómo funciona, cómo se
ejecuta, qué recorre cada caso paso a paso y qué defecto encontró.

## Contenido

- [Lenguaje y herramientas](#lenguaje-y-herramientas)
- [Estructura de archivos](#estructura-de-archivos)
- [Cómo se ejecuta](#cómo-se-ejecuta)
- [Cómo funcionan](#cómo-funcionan)
- [Resumen de cobertura](#resumen-de-cobertura)
- [CU-18: configuración de la tienda](#cu-18-configuración-de-la-tienda)
- [CU-19: devolución de una compra](#cu-19-devolución-de-una-compra)
- [CU-20: reportes de contenido](#cu-20-reportes-de-contenido)
- [CU-21: moderación de reportes](#cu-21-moderación-de-reportes)
- [CU-22: restricciones de cuentas](#cu-22-restricciones-de-cuentas)
- [Defecto encontrado al automatizar: errores de Mi tienda](#defecto-encontrado-al-automatizar-errores-de-mi-tienda)
- [Cómo agregar la automatización de otro caso de uso](#cómo-agregar-la-automatización-de-otro-caso-de-uso)

## Lenguaje y herramientas

| Parte | Lenguaje / herramienta |
| --- | --- |
| Pruebas automatizadas | **TypeScript** con **Playwright** (`@playwright/test`), el mismo lenguaje del frontend Angular |
| Configuración de cada demostración | TypeScript (`frontend/playwright.cuNN-demo.config.ts`) |
| Ejecución | Scripts de npm en `frontend/package.json` (`demo:cu18` … `demo:cu22`) |
| Servidor de la aplicación durante la prueba | `npm run build` + `node e2e-security-server.cjs` (lo arranca Playwright solo) |
| Datos para la prueba manual del CU-22 | Bash + SQL (`scripts/cu22-demo-seed.sh`) |

Se eligió TypeScript con Playwright porque el frontend ya está en TypeScript y el proyecto ya usaba Playwright en sus
pruebas E2E: el equipo no necesita otro lenguaje ni otra herramienta para mantenerlas.

## Estructura de archivos

```text
frontend/
├── e2e-cu18-demo/mi-tienda-demo.spec.ts          # CU-18
├── e2e-cu19-demo/devolucion-demo.spec.ts         # CU-19
├── e2e-cu20-demo/reportes-demo.spec.ts           # CU-20
├── e2e-cu21-demo/moderacion-demo.spec.ts         # CU-21
├── e2e-cu22-demo/restricciones-demo.spec.ts      # CU-22
├── e2e/mi-tienda-errores.spec.ts                 # prueba de regresión (corre en la CI)
├── playwright.cu18-demo.config.ts … playwright.cu22-demo.config.ts
└── package.json                                  # scripts demo:cu18 … demo:cu22
scripts/cu22-demo-seed.sh                         # datos para probar CU-22 a mano
```

## Cómo se ejecuta

Desde la carpeta `frontend`, con el comando de cada caso. No hace falta levantar Docker, MySQL ni el backend.

| Caso | Comando | Puerto | Cuenta(s) | Duración aprox. | Informe HTML |
| --- | --- | --- | --- | --- | --- |
| CU-18 | `npm.cmd run demo:cu18` | 4452 | `vendedor.demo@example.com` | 2 min | `playwright-report-cu18-demo` |
| CU-19 | `npm.cmd run demo:cu19` | 4451 | `comprador.demo@example.com`, `vendedor.demo@example.com` | 3 min | `playwright-report-cu19-demo` |
| CU-20 | `npm.cmd run demo:cu20` | 4453 | `comprador.demo@example.com` | 2 min | `playwright-report-cu20-demo` |
| CU-21 | `npm.cmd run demo:cu21` | 4454 | `soporte.demo@example.com` | 2 min | `playwright-report-cu21-demo` |
| CU-22 | `npm.cmd run demo:cu22` | 4450 | `admin.demo@example.com`, `demo@marketplace.local` | 3 min | `playwright-report-cu22-demo` |

Cada configuración usa un puerto propio para que las demostraciones no choquen entre sí ni con otras pruebas. Al
terminar, el informe se abre desde `frontend` con:

```powershell
npx.cmd playwright show-report playwright-report-cu22-demo   # cambiar el número del caso
```

Las carpetas de informe están en `.gitignore`.

## Cómo funcionan

1. **El comando compila el frontend y lo sirve** en el puerto de la tabla.
2. **Chrome se abre visible** (`headless: false`) y las acciones se ejecutan con una pausa de 700 ms para poder
   seguirlas en una exposición. La pausa se configura en `launchOptions.slowMo` del archivo
   `playwright.cuNN-demo.config.ts` y no representa el tiempo de respuesta del sistema.
3. **La API está simulada dentro de la prueba** con `page.route('**/api/**', …)`. La simulación guarda su propio
   estado durante el recorrido: lo que hace un actor (restringir, aprobar, retirar) cambia lo que se ve después.
4. **Un aviso naranja narra cada etapa** en la esquina inferior derecha (función `narrar`).
5. **Cada paso verifica lo que aparece en pantalla** con `expect(...)`. Si algo no coincide, la prueba se detiene en
   rojo e indica el paso. Entre las cinco hay 114 verificaciones.

### Por qué la API está simulada

- **Repetibles:** siempre parten del mismo estado; no dependen de datos que cambian entre ejecuciones.
- **Sin infraestructura:** salen igual en cualquier computador.
- **Varios actores en un solo recorrido:** la simulación representa la parte de otro actor o de un proceso que en el
  sistema real tarda horas o días (llegada de un paquete, fin de una inspección, respuesta de soporte).

Esto aplica la táctica de **sustitución de dependencias** del atributo de testabilidad (ASR-007).

### Qué demuestran y qué no

Demuestran la **interfaz y el flujo completo de cada caso de uso**, incluidos los caminos de error. No certifican las
reglas del backend: esas las comprueban las pruebas de integración del backend contra MySQL real, que corren en la CI
(job «Maven verification»). Cada sección indica cuáles.

## Resumen de cobertura

| CU | Camino principal | Validaciones y errores comprobados |
| --- | --- | --- |
| CU-18 Mi tienda | Editar la tienda, ver la vista previa y guardar con nueva versión | Plazo de devolución menor a 30 días rechazado junto al campo |
| CU-19 Devoluciones | Solicitar, revisar, aprobar, recoger, inspeccionar y reembolsar | Solicitud de información de la tienda y respuesta del comprador |
| CU-20 Reportes | Reportar con evidencia, responder a soporte y ver el resultado | Motivo de compra redirigido, descripción vacía, reporte duplicado |
| CU-21 Moderación | Tomar el caso, ocultar, pedir información y retirar | Decisiones bloqueadas con una solicitud de información abierta |
| CU-22 Restricciones | Evaluar, restringir un rol, responder y retirar la restricción | Rol restringido rechazado mientras el otro rol sigue funcionando |

---

## CU-18: configuración de la tienda

**Comando:** `npm.cmd run demo:cu18` · **Archivo:** `e2e-cu18-demo/mi-tienda-demo.spec.ts` · **Cuenta:**
`vendedor.demo@example.com`

1. Abre «Mi tienda»: ve la configuración guardada y cómo ven la tienda los compradores.
2. Edita y baja el plazo de devolución a 15 días. El sistema no deja continuar y explica, junto al campo, que el
   mínimo obligatorio del marketplace es de 30 días (flujo alterno A5). No se envía nada.
3. Sube el plazo a 45 días, completa descripción, teléfono, horarios y política, agrega el envío exprés y elige una
   imagen de portada.
4. Revisa la vista previa: cada cambio aparece como antes → después y la tarjeta muestra la tienda como quedaría.
   Todavía no se ha guardado nada.
5. Confirma: se suben la portada y la configuración, y la tienda queda guardada con su nueva versión (3 → 4).

**Qué demuestra y qué no.** La API simulada rechaza, como el backend, un plazo menor al mínimo y una versión
desactualizada. No certifica las reglas del backend (nombre único, versión, formatos e imágenes), que comprueban sus
pruebas en la CI. La prueba de regresión `e2e/mi-tienda-errores.spec.ts` se explica en
[Defecto encontrado al automatizar](#defecto-encontrado-al-automatizar-errores-de-mi-tienda).

## CU-19: devolución de una compra

**Comando:** `npm.cmd run demo:cu19` · **Archivo:** `e2e-cu19-demo/devolucion-demo.spec.ts` · **Cuentas:**
`comprador.demo@example.com` y `vendedor.demo@example.com`, alternadas en un solo navegador

1. El comprador abre un pedido entregado, pulsa «Devolver», elige el motivo y describe la falla. Queda «Solicitada».
2. La tienda abre «Devoluciones recibidas», inicia la revisión y pide más información (24 horas para responder).
3. El comprador ve «Requiere tu respuesta» en «Mis devoluciones» y responde.
4. La tienda lee la respuesta y aprueba con una nota.
5. El comprador elige «Recogida en tu dirección» y ve el seguimiento del envío de retorno.
6. Logística entrega el paquete en la tienda: empiezan las 24 horas de inspección.
7. Sin problemas reportados, el sistema reembolsa. El comprador ve la devolución «Finalizada» y el historial completo.

**Qué demuestra y qué no.** Cada acción de una parte cambia lo que la otra ve después. La llegada del paquete (en el
sistema real la publica logística, CU-25) y el fin de la inspección (lo hace un barrido programado cada minuto) se
simulan entre etapas, sin esperar horas reales. Las reglas del backend las comprueban las pruebas del paquete
`returns` (por ejemplo `ReturnBuyerApiTests`, `ReturnSellerApiTests`, `ReturnInspectionSweepTests` y
`ReturnMethodSelectionTests`) en la CI. El flujo está en
[CU-19: devolución de compra](../casos-de-uso/cu-19-devolucion-compra.md).

## CU-20: reportes de contenido

**Comando:** `npm.cmd run demo:cu20` · **Archivo:** `e2e-cu20-demo/reportes-demo.spec.ts` · **Cuenta:**
`comprador.demo@example.com`

1. En el catálogo, un reloj «original con garantía oficial» a un precio imposible. El comprador pulsa «Reportar».
2. Elige «El producto llegó defectuoso o distinto»: el formulario explica que es un problema de compra, lo orienta a
   reclamaciones y devoluciones y no deja enviar.
3. Elige «Información engañosa o falsa» e intenta enviar sin describir: el error aparece junto a la descripción.
4. Describe el problema, adjunta una captura como evidencia y radica: recibe el reporte #214, Pendiente.
5. Lo reporta otra vez con el mismo motivo: no se crea otro; el sistema lo lleva a su reporte #214 con la evidencia.
6. Soporte toma el caso, oculta la publicación por precaución (desaparece del catálogo) y pide información. En «Mis
   reportes» aparece «Requiere tu respuesta» y el comprador responde dentro de las 72 horas.
7. Soporte retira la publicación. El comprador ve «El contenido fue retirado», pero no quién decidió ni por qué.

**Qué demuestra y qué no.** Lo que hace soporte en los pasos 6 y 7 pertenece a CU-21; aquí solo se simula su efecto
para ver lo que recibe el reportante. Las reglas del backend (contenido propio, duplicados, plazos, imágenes) las
comprueban las pruebas del módulo `reports` en la CI. El contrato está en
[CU-20: reportes de contenido](../casos-de-uso/cu-20-reportes-contenido.md).

## CU-21: moderación de reportes

**Comando:** `npm.cmd run demo:cu21` · **Archivo:** `e2e-cu21-demo/moderacion-demo.spec.ts` · **Cuenta:**
`soporte.demo@example.com`

1. Abre «Moderación de reportes»: un caso de una publicación con 2 reportes de personas distintas.
2. En el detalle ve el contenido, cada reporte con su motivo, la evidencia y un caso anterior que se mantuvo.
3. Toma el caso; queda asignado a su nombre. Toda acción exige una justificación de al menos 10 caracteres.
4. Oculta la publicación temporalmente, por precaución, mientras investiga.
5. Pide información al primer reportante (72 horas). Con la solicitud abierta no se puede mantener ni retirar.
6. El reportante responde (eso es CU-20, aquí simulado). El agente ve la respuesta y retira la publicación.
7. El caso queda resuelto, con la decisión y su justificación, y aparece en «Casos resueltos».

**Qué demuestra y qué no.** La API simulada rechaza, como el backend, decidir con una solicitud de información vigente
o con una versión desactualizada del caso. Las reglas del backend las comprueban las pruebas del módulo de moderación
en la CI. Una publicación retirada cuenta después como antecedente de la tienda en CU-22.

## CU-22: restricciones de cuentas

**Comando:** `npm.cmd run demo:cu22` · **Archivo:** `e2e-cu22-demo/restricciones-demo.spec.ts` · **Cuentas:**
`admin.demo@example.com` y `demo@marketplace.local`, alternadas en un solo navegador

1. `admin.demo@example.com` inicia sesión, abre «Restricciones de cuentas» y busca `demo@marketplace.local`: 2
   antecedentes en 180 días (una reclamación con reembolso y una publicación retirada) y la marca «Reincidente».
2. Pide una aclaración y aplica una restricción de 30 días solo al rol Vendedor.
3. `demo@marketplace.local` inicia sesión: el rol Vendedor se rechaza con el aviso de restricción vigente
   (`403 ROLE_RESTRICTED`) y el rol Comprador sí funciona.
4. En «Mi cuenta» ve la restricción y su motivo, sin la identidad del administrador, y responde la aclaración.
5. La administración vuelve, lee la respuesta en la evaluación y retira la restricción antes de su fin.
6. `demo@marketplace.local` vuelve a elegir el rol Vendedor sin problema.

**Qué demuestra y qué no.** Restringir, responder y retirar cambian lo que se ve después. Las reglas del backend las
comprueban las pruebas de integración `AccountRestrictionIntegrationTests` (13 pruebas, en la CI) contra MySQL real;
ver [CU-22: restricciones de cuentas](../casos-de-uso/cu-22-restricciones.md).

### Prueba manual contra el sistema real

Además de la demostración simulada, el CU-22 se puede probar a mano contra el backend y MySQL reales. El script
`scripts/cu22-demo-seed.sh` prepara los antecedentes de la cuenta de demostración con textos realistas y es
idempotente (puede ejecutarse varias veces sin duplicar datos). Desde la raíz del repositorio, en Git Bash:

```bash
DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' .env)" MYSQL_CONTAINER=transformers-as-mysql-1 bash scripts/cu22-demo-seed.sh
```

Con `RESET_RESTRICTIONS=1` además retira las restricciones vigentes para repetir la prueba desde cero. El paso a
paso está en [CU-22: restricciones de cuentas](../casos-de-uso/cu-22-restricciones.md).

---

## Defecto encontrado al automatizar: errores de Mi tienda

Al automatizar CU-18 se encontró un error real de la interfaz: cuando el vendedor ingresaba un valor inválido (por
ejemplo, un plazo de devolución menor a 30 días), el formulario mostraba la alerta general «Revisa los campos
marcados», pero **no mostraba el mensaje junto al campo**, así que el usuario no sabía qué corregir.

- **Causa:** con Ionic 8.8.19, el `errorText` de `ion-input` no se dibuja porque Ionic sobrescribe las clases
  `ion-invalid` / `ion-touched` de las que depende.
- **Corrección:** `frontend/src/app/panel-vendedor/components/mi-tienda.component.ts` deja de usar `errorText` en los
  siete campos validados y muestra cada error con un párrafo propio (`<p class="error-campo" id="error-<campo>"
  role="alert">`) debajo del campo.
- **Prueba de regresión:** `frontend/e2e/mi-tienda-errores.spec.ts` ingresa un plazo de 15 días y un teléfono
  inválido, y comprueba que aparecen `#error-returnWindowDays` y `#error-contactPhone`, que no se marca un campo
  válido y que los mensajes desaparecen al corregir. Se verificó que **falla con el código anterior y pasa con la
  corrección**.

Esta prueba está en `frontend/e2e/`, la carpeta que ejecuta la CI (job «Frontend E2E with Playwright»): si el
defecto vuelve a aparecer, la CI lo detecta. La corrección apoya el requisito de usabilidad RNF-027 (mensajes por
campo y conservación de datos).

## Cómo agregar la automatización de otro caso de uso

1. Copiar una carpeta `e2e-cuNN-demo/` y su `playwright.cuNN-demo.config.ts`, y cambiar el puerto por uno libre.
2. Agregar el script `demo:cuNN` en `frontend/package.json`.
3. Agregar `frontend/playwright-report-cuNN-demo/` al `.gitignore`.
4. Documentar el recorrido en una nueva sección de este documento (o en un documento equivalente para ese caso) y
   enlazarlo en `docs/README.md`.
5. Si la automatización encuentra un defecto, agregar además una prueba de regresión en `frontend/e2e/` para que la
   ejecute la CI.
