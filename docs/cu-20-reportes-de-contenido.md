# CU-20 · Radicar y consultar reportes de contenido

Compradores y vendedores con el rol activo reportan publicaciones ajenas y consultan sus reportes. La revisión la hace
CU-21 (moderación); CU-20 solo radica, muestra el estado y recibe las respuestas del reportante.

## API (`/api/reports`, rol activo COMPRADOR o VENDEDOR)

| Método y ruta | Qué hace |
|---|---|
| `GET /reasons` | Todos los motivos con la bandera `purchaseProblem`. |
| `POST /` | Radica. JSON, o `multipart/form-data` con los campos y hasta 3 imágenes en `evidences`. 201 si es nuevo; 200 con `duplicate: true` si ya había uno activo con ese motivo. |
| `GET /mine`, `GET /{id}` | Sus reportes y el detalle. El de otra persona responde 404. |
| `POST /{id}/information-requests/{requestId}/response` | Responde una solicitud del agente (plazo de 72 h). |
| `GET /{id}/evidences/{n}` | Una imagen propia. `Cache-Control: private, no-cache` con `ETag`. |
| `GET /api/support/moderation/reports/{id}/evidences/{n}` | La misma imagen para el agente (rol SOPORTE). |

Errores con código: `REPORTER_ROLE_REQUIRED` (403), `REPORT_FIELD_REQUIRED` / `REPORT_FIELD_INVALID` (400, con
`details.field`), `IMAGE_*` y `EVIDENCE_TOO_MANY` (400), `CONTENT_NOT_FOUND` (404), `REPORT_OWN_CONTENT` (403),
`REPORT_ALREADY_OPEN` (409, con `details.reportId`), `REPORT_REASON_IS_PURCHASE_PROBLEM` y
`CONTENT_TYPE_NOT_REPORTABLE` (422), `INFORMATION_REQUEST_EXPIRED` (410) e `INFORMATION_REQUEST_ALREADY_ANSWERED` (409).

## Qué ve el reportante

Estado traducido: `PENDIENTE`, `EN_REVISION`, `ESPERANDO_INFORMACION`, `RESUELTO`. Con el caso resuelto, solo el
resultado `MANTENIDO` o `RETIRADO`. Mientras el caso siga abierto y el contenido oculto por precaución, `medidaProvisional: OCULTO`.
Nunca la justificación, el agente ni otros reportantes; el propietario del contenido nunca ve quién reportó (RF-161).

## Configuración

`reports.evidence.max-count` (3) y `reports.evidence.max-size` (5MB). La petición multipart completa admite 16 MB
(`spring.servlet.multipart.max-request-size`); MySQL 8.4 usa `max_allowed_packet` de 64 MB por defecto, que cubre 3 × 5 MB.

## Limitaciones conocidas

- **Otro motivo del mismo usuario (paso 9 del Excel).** CU-21 rechaza que un usuario sume dos reportes a un caso
  abierto. Por eso, quien ya reportó un contenido con un motivo y lo reporta con otro recibe 409 `REPORT_ALREADY_OPEN`
  con el id del reporte existente, en vez de agregar un segundo reporte. Para cambiarlo hay que modificar CU-21.
- **Cierre por información insuficiente (A8).** CU-21 no lo modela como resultado. Si el plazo de 72 h vence, el caso
  vuelve a `EN_REVISION` y el agente decide; lo habitual es mantener el contenido, y el reportante ve `MANTENIDO`. No se
  añadió un resultado `CERRADO_SIN_INFORMACION`.
- **Aviso al agente al radicar.** CU-21 no notifica al radicar: el caso aparece en su cola. Solo se avisa al agente
  asignado cuando el reportante responde una solicitud.
- **Tipos de contenido.** Solo `PUBLICACION`. Reseñas, respuestas, mensajes y tiendas responden 422 hasta que su módulo
  registre un `ReportableContentVerifier` y un `ContentOwnerResolver`.
- **Migración.** `V25__create_report_evidence_files.sql` es provisional: `origin/Alejandro` ya usa V23 y V24 y CU-19
  reservaba V23, así que puede que haya que renumerarla al integrar.
- **Multipart.** El tope de 16 MB es global y lo necesita también CU-19; está en su propio commit para poder revertirlo.

## Datos de demostración

`scripts/cu20-demo-seed.sh` crea las cuentas y las publicaciones para probar cada alternativa (ver su cabecera).
