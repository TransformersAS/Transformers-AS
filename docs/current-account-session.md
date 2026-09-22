# CU-08: permisos vigentes durante la sesión

## Modelo existente

- `AccountStatus` y `user_accounts.status`: ACTIVA / INACTIVA.
- `user_account_roles`: pertenencia `(account_id, role)`, sin columna de estado ni
  modelo persistido de rol restringido. `SellerRegistrationService.grantSellerRole`
  agrega VENDEDOR mediante `UserAccountRepository.save`.
- `ReportModerationService.referToAccountAdmin` solo registra una remisión; no cambia
  estado de cuenta ni roles. No hay un flujo general implementado para sancionarlos.
- `stores.status` sí admite RESTRICTED / SUSPENDED, pero restringe operaciones sobre
  la tienda mediante `StatusBasedModificationPolicy`. No equivale a retirar VENDEDOR.
  Esta política se conserva.

## Estrategia

`CurrentAccountSessionFilter` se registra únicamente dentro de Spring Security,
inmediatamente después de `SecurityContextHolderFilter`: la identidad JDBC ya está
cargada y la comprobación ocurre antes de CSRF, logout y autorización de endpoints.
Para cada petición con `AccountPrincipal` autenticado consulta `UserAccountRepository.findById`.
No usa caché ni depende de que el cambio se realice por un controller específico; también
reconoce cambios SQL confirmados antes de la siguiente petición.

- Cuenta ausente o INACTIVA: invalida la sesión actual con `SecurityContextLogoutHandler`,
  elimina su contexto y responde 401. Las demás sesiones de esa cuenta se comprueban e
  invalidan cuando vuelven a usarse. Reactivar la cuenta no restaura cookies ya invalidadas.
- Roles modificados: reemplaza el principal y sus autoridades por los valores vigentes,
  y guarda el contexto actualizado en Spring Session JDBC. Conserva el rol activo solo si
  aún pertenece a la cuenta; de lo contrario queda NULL y los endpoints de ese rol dan 403.
- Nunca selecciona otro rol automáticamente, aunque solo quede uno. Tampoco activa un
  rol agregado/restablecido hasta que el usuario lo seleccione explícitamente.
- `/api/auth/me` y `SessionController.changeRole` reciben el principal actualizado por el
  filtro. `/me` devuelve los roles vigentes; si la cuenta está inactiva, devuelve 401 y no
  una representación antigua de la cuenta. El contrato JSON permanece igual.
- Los principals nuevos no incluyen contraseña ni hash. Se crea un contexto nuevo para
  no modificar en memoria el objeto compartido por otras peticiones concurrentes.

No cambia cookies, duración normal/persistente, CSRF, JWT, frontend ni esquema de datos.
Solo escribe un contexto actualizado si cambió la identidad o la lista de roles.
Añade una consulta del estado de cuenta (y su colección de roles) por petición autenticada.
Un fallo de lectura no autoriza usando los datos anteriores. Las peticiones que ya estaban
procesándose antes de un cambio no se cancelan; la siguiente petición vuelve a comprobarlo.

## Pruebas

`CurrentAccountSessionIntegrationTests` usa MySQL/Testcontainers y login real, cookie,
CSRF y sesiones JDBC en ambas modalidades. Cambia persistencia después del login y prueba
cuenta inactiva/eliminada, retiro de cada rol, selección prohibida de roles retirados,
`/me` actualizado, ausencia de selección automática, cuentas no afectadas, duración de
sesión, ausencia de credenciales serializadas, CSRF, logout y revocación.

Resultados finales de Maven (`test`):

- `CurrentAccountSessionIntegrationTests`: 16 pruebas aprobadas.
- `SessionAuthenticationTests`: 37 pruebas aprobadas.
- `PersistentSessionIntegrationTests`: 23 pruebas aprobadas.
- `BuyerIsolationIntegrationTests`: 14 pruebas aprobadas.
- `SellerRegistrationTests`: 14 pruebas aprobadas.
- `SupportSessionIntegrationTests`: 3 pruebas aprobadas.
- `SellerStoreAuthorizationTests`: 5 pruebas aprobadas.

Total: 112 pruebas aprobadas. La prueba nueva de revocación se ajustó para usar el
identificador público de `/api/auth/sessions`, conforme al contrato existente, y se volvió
a ejecutar la suite nueva completa. `git diff --check` sin errores.
