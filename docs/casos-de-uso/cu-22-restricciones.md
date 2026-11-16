# CU-22 · Administrar cuentas y restricciones de acceso

El administrador busca una cuenta por su correo, revisa sus antecedentes, puede pedirle una aclaración con plazo de
72 h y aplica, modifica o retira una restricción sobre toda la cuenta o sobre uno de sus roles, temporal o permanente.
La propia persona ve sus restricciones vigentes y responde las aclaraciones desde «Mi cuenta». El código está en el
módulo `restrictions` (backend) y en `frontend/src/app/restricciones`.

## Reglas

- **Alcance.** `ACCOUNT` (toda la cuenta) o `ROLE` con un rol (`COMPRADOR`, `VENDEDOR`, `SOPORTE`, `ADMIN`). Como
  máximo una restricción vigente por alcance: la cuenta puede tener a la vez una de cuenta completa y una por rol.
- **Duración.** Temporal de 1 a 3650 días contados desde el inicio, o permanente. Motivo y justificaciones de 10 a 1000
  caracteres.
- **Modificar (A7).** Cambiar la duración total (solo temporales; no puede dejar el fin en el pasado: para eso se
  retira), volverla permanente o volverla temporal con una duración desde ahora. Todo cambio exige justificación. El
  alcance no se modifica: se retira y se aplica otra.
- **Retirar (A6)** antes de su fin, con motivo. **Fin automático (A5):** el barrido cierra como `EXPIRED` las temporales
  vencidas, cada una en su propia transacción y con compare-and-set, así varias réplicas pueden correrlo a la vez.
- **Aclaraciones (A3, A4).** Una abierta por cuenta, con 72 h para responder. Vencida, ya no se admite la respuesta y
  el administrador puede pedir otra.
- **Antecedentes (pasos 4 a 6).** Las reclamaciones de su tienda resueltas por soporte con `REFUND_GRANTED` (CU-13), las
  decisiones de moderación `RETIRAR` u `OCULTAR_TEMPORALMENTE` sobre su tienda o sus publicaciones (CU-21) y sus
  restricciones anteriores ya cerradas. Es **reincidente** con 2 o más antecedentes en 180 días, o con cualquier
  restricción anterior (mismo criterio que CU-27).
- **Efecto sobre la tienda.** Si la restricción es de toda la cuenta o del rol `VENDEDOR` y la cuenta tiene tienda, la
  tienda pasa a `RESTRICTED` en la misma transacción: no puede publicar ni vender, pero atiende lo que ya tenía (igual
  que una suspensión de CU-27). Al cerrarse vuelve a `ACTIVE` solo si no queda otra restricción que le impida vender.
  Una tienda `SUSPENDED` no se toca: CU-27 ya la bloquea. `FindSellerAccountStanding` (users) también tiene en cuenta
  las restricciones, así CU-27 no reactiva la tienda de una cuenta restringida.
- **Efecto sobre el acceso.** La cuenta restringida **puede iniciar sesión**, para consultar la medida y responder
  las aclaraciones en «Mi cuenta», pero **no puede usar el rol restringido** (o ningún rol, si la restricción es de
  toda la cuenta):
  - `PUT /api/auth/active-role` con ese rol responde 403 `ROLE_RESTRICTED`.
  - Al iniciar sesión, una cuenta de un solo rol restringido queda sin rol activo.
  - Una sesión ya abierta pierde el rol en su siguiente petición: `CurrentAccountSessionFilter` consulta
    `AccountRestrictionReader` y, si el rol activo está restringido, lo quita del principal. Sin rol activo, todos
    los endpoints de ese rol responden 403; `/api/account/**` y `/api/auth/**` siguen disponibles.
  - Al retirarse o vencer la restricción, el rol vuelve a poder elegirse; no se activa solo.
  - En el frontend, una sesión sin rol activo tiene un menú con «Mi cuenta», y el cambio de rol rechazado muestra el
    mensaje del servidor.
- **Trazabilidad.** Cada aplicación, cambio, retiro y vencimiento queda en `account_restriction_changes` y en
  `audit_logs` (`entity_type = 'ACCOUNT'`; actor `SYSTEM` en el fin automático). La persona recibe una notificación
  interna en cada paso (`ACCOUNT_RESTRICTED`, `ACCOUNT_RESTRICTION_CHANGED`, `ACCOUNT_RESTRICTION_ENDED`,
  `ACCOUNT_INFORMATION_REQUESTED`).

## API

Administración (`/api/admin`, rol activo `ADMIN`). El administrador responsable siempre sale de la sesión.

| Método y ruta | Qué hace |
|---|---|
| `GET /accounts?email=` | Busca la cuenta por correo exacto. 404 `ACCOUNT_NOT_FOUND`. |
| `GET /accounts/{id}/evaluation` | Cuenta, antecedentes, reincidencia, restricciones (vigentes y todas, con su historia) y aclaraciones. |
| `POST /accounts/{id}/information-requests` | `{message}`. Pide una aclaración. 201. |
| `POST /accounts/{id}/restrictions` | `{scope, role, reason, permanent, durationDays}`. Aplica. 201. |
| `PUT /restrictions/{id}/duration` | `{durationDays, justification}`. Nueva duración total. |
| `POST /restrictions/{id}/permanent` | `{justification}`. La vuelve permanente. |
| `POST /restrictions/{id}/temporary` | `{durationDays, justification}`. La vuelve temporal desde ahora. |
| `POST /restrictions/{id}/withdraw` | `{reason}`. La retira. Responde `{restrictionId, status}`. |

Propia cuenta (`/api/account`, cualquier rol autenticado). La cuenta sale de la sesión; no ve quién aplicó la medida.

| Método y ruta | Qué hace |
|---|---|
| `GET /standing` | Sus restricciones vigentes y sus aclaraciones. |
| `POST /information-requests/{id}/answer` | `{response}`. Responde dentro del plazo. La de otra cuenta responde 404. |

Los cuerpos son estrictos: una propiedad desconocida responde 400. Errores con código: `RESTRICTION_REASON_REQUIRED`,
`JUSTIFICATION_REQUIRED`, `INFORMATION_MESSAGE_REQUIRED`, `INFORMATION_RESPONSE_REQUIRED`,
`RESTRICTION_DURATION_INVALID`, `RESTRICTION_DURATION_UNCHANGED`, `RESTRICTION_END_IN_PAST`,
`RESTRICTION_SCOPE_ROLE_MISMATCH` (400, con `details.field`); `RESTRICTION_SCOPE_INVALID`, `RESTRICTION_ROLE_INVALID`
(400); `ROLE_RESTRICTED` (403, en `PUT /api/auth/active-role`); `ACCOUNT_NOT_FOUND`, `RESTRICTION_NOT_FOUND`, `INFORMATION_NOT_FOUND` (404); `ACCOUNT_ALREADY_RESTRICTED`,
`RESTRICTION_NOT_ACTIVE`, `RESTRICTION_ALREADY_PERMANENT`, `RESTRICTION_ALREADY_TEMPORARY`, `RESTRICTION_IS_PERMANENT`,
`RESTRICTION_CONCURRENT_UPDATE`, `INFORMATION_ALREADY_OPEN`, `INFORMATION_ALREADY_ANSWERED`, `INFORMATION_EXPIRED` (409).

## Pantallas

- **Administración → «Restricciones de cuentas»**: buscar, antecedentes, restricciones vigentes con sus acciones,
  pedir aclaración, aplicar, e historial de restricciones y de aclaraciones.
- **«Mi cuenta»** (en el menú de los cuatro roles): restricciones vigentes con motivo y fin, y las aclaraciones con un
  formulario para responder las abiertas.

## Configuración y base de datos

- `restrictions.sweep.enabled` (`true`) y `restrictions.sweep.interval` (`PT1M`). Las pruebas lo apagan y llaman a
  `ExpireDueRestrictionsUseCase.runOnce()` directamente.
- Migración `V51__create_account_restrictions.sql`: `account_restrictions`, `account_restriction_changes` y
  `account_information_requests`. La unicidad por alcance vigente y la de una aclaración abierta por cuenta las
  garantiza la base (`active_key` y `open_key`), no solo el código.

## Pruebas

- Unitarias del dominio y de los casos de uso en `src/test/java/.../restrictions/`.
- `AccountRestrictionIntegrationTests`: de punta a punta contra MySQL (Testcontainers). Cubre antecedentes reales
  (los no confirmados no cuentan), aclaraciones y su vencimiento, restricción de rol y de cuenta con efecto sobre la
  tienda y sobre la venta, el efecto sobre el acceso (sesión abierta, nuevo inicio de sesión, cuenta de un solo rol y
  rol recuperado al retirar), cambios, retiro con varias restricciones a la vez, el barrido y los permisos.
- `AbstractIntegrationTest` limpia las tres tablas nuevas entre pruebas.

## Limitaciones conocidas

- **Una consulta más por petición.** El filtro de sesión consulta las restricciones vigentes en cada petición
  autenticada con rol activo (sin caché, como ya hace con el estado de la cuenta).
- **Fin automático con hasta un minuto de retraso.** El rol se recupera cuando el barrido cierra la restricción, no
  en el instante exacto de `ends_at`.
- **Reincidencia.** Cualquier restricción anterior cuenta, incluso una retirada antes de su fin; si se retiró por error,
  igual marca la cuenta como reincidente.
- **Suspensión paralela.** `endRestriction` no comprueba si CU-27 tiene una suspensión vigente sobre la misma tienda;
  no ocurre en la práctica porque una tienda `SUSPENDED` no pasa a `RESTRICTED` (ver
  `ChangeStoreOperationalStatusUseCase`).

## Datos de demostración

Con el backend arrancado al menos una vez con `MARKETPLACE_DEMO_DATA=true`:

```bash
DB_PASSWORD='la-clave-de-marketplace_app' ./scripts/cu22-demo-seed.sh            # agrega los antecedentes
RESET_RESTRICTIONS=1 DB_PASSWORD='...' ./scripts/cu22-demo-seed.sh               # además borra las restricciones
```

Le da a la tienda 1 una reclamación con reembolso y una publicación retirada por moderación. Luego, con la contraseña
`MarketplaceDemo123!`:

1. `admin.demo@example.com` → menú «Administración» → «Restricciones de cuentas» → buscar `demo@marketplace.local`.
   Aparecen 2 antecedentes y la marca «Reincidente».
2. Pedir aclaración y aplicar una restricción al rol Vendedor: la tienda 1 queda `RESTRICTED`.
3. `demo@marketplace.local`: el rol Vendedor ya no se puede elegir (aparece el aviso) y, sin rol activo o con el de
   Comprador, abre «Mi cuenta»: ve la restricción y responde la aclaración.
4. De vuelta como administración: la respuesta aparece en la evaluación; retirar la restricción y la tienda vuelve a
   `ACTIVE`.

`npm run start` muestra la página en blanco mientras `angular.json` tenga `subresourceIntegrity: true` para todas las
configuraciones (Chrome bloquea los scripts que `ng serve` reescribe). Es un problema general del frontend, no de este
caso de uso; para la demo con Docker (`compose.yaml`) no aplica.
