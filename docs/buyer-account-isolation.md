# CU-08: rol activo y aislamiento del comprador

`SecurityConfiguration.securityFilterChain` exige `ROLE_COMPRADOR` en `/api/cart` y sus
subrutas, `/api/addresses` y sus subrutas, `/api/checkout/preview`, `/api/reservations/cart`
y `/api/payments/process`. Las autoridades de `AccountPrincipal` representan solamente
el rol activo. Tener COMPRADOR entre los roles disponibles no concede acceso mientras
esté activo otro rol o no se haya elegido uno.

Los controllers obtienen la identidad con `@AuthenticationPrincipal` y
`BuyerAccess.accountId`. La cuenta nunca se acepta del body, query string ni headers.
Los services/use cases reciben ese `accountId` y los repositories consultan por propietario:

| Flujo | Controller → service/use case | Repository / tabla |
| --- | --- | --- |
| Carrito | CartController → CartService | CartRepository.findByAccountId; CartItemRepository.findByIdAndCart_AccountId / carts, cart_items |
| Direcciones | AddressController → AddressService | AddressRepository.findByAccountId / addresses |
| Checkout | CheckoutController → CheckoutService.preview | AddressRepository.existsByIdAndAccountId; CartRepository.findByAccountId |
| Reserva | InventoryReservationController → InventoryReservationService.reserveCart | CartRepository.findByAccountId; inventory_reservations.account_id |
| Pago | PaymentController → ProcessPaymentUseCase.execute | InventoryReservationService.validateOwnership antes de PaymentGateway.process; InventoryReservationRepository.findByAccountIdAndIdIn |
| Pedido aprobado | ProcessPaymentUseCase → CreateOrderUseCase.execute | Carrito y dirección por accountId; orders.account_id; solo se vacían los items de ese carrito |

Los IDs ajenos e inexistentes reciben el mismo 404. La validación de reservas se hace
para todos los resultados de pago, incluido PENDING y REJECTED, para no confirmar ni
liberar reservas de otro comprador. Una lista mixta se rechaza completa antes de llamar
a la pasarela. Se mantienen CSRF, cookies, restauración y revocación de sesiones.

## Migración V32

Agrega `account_id` con FK a `user_accounts` en `carts`, `addresses` e
`inventory_reservations`, índices de propietario y unicidad del carrito por cuenta.
Los registros globales previos quedan con propietario NULL: se conservan, pero no se
muestran, adoptan ni utilizan en nuevas compras. No hay información fiable para asignarlos.
Los compradores tendrán un carrito propio nuevo y deberán registrar sus direcciones.
Las referencias históricas de pedidos permanecen intactas. Las reservas previas siguen
su vencimiento normal y cuentan para la disponibilidad de stock hasta entonces.

No se cambian contratos del frontend ni sus archivos. La política y la comprobación de
propiedad se ejecutan en el backend.

## Pruebas

`BuyerIsolationIntegrationTests` usa sesiones reales, CSRF y MySQL/Testcontainers. Cubre
roles activos, cambio de rol, ausencia de rol, acceso anónimo, selectores de cuenta falsificados,
IDs ajenos, aislamiento entre dos compradores, las tres respuestas de pago, inexistencia
de efectos secundarios ante un rechazo y registros antiguos sin propietario.
`BuyerOwnershipMigrationTests` prueba actualización V31→V32 con datos previos, conservación
de registros, FKs y carrito único por cuenta. Las pruebas anteriores de compra se actualizan
para crear recursos del comprador correspondiente.

Validación ejecutada con Maven (`test`), con los siguientes resultados finales:

- `BuyerIsolationIntegrationTests`: 14 pruebas aprobadas.
- `BuyerOwnershipMigrationTests`: 1 pruebas aprobadas.
- `CheckoutReservationValidationTests`: 11 pruebas aprobadas.
- `CheckoutReservationIntegrationTests`: 26 pruebas aprobadas.
- `CheckoutOrderCreationTests`: 3 pruebas aprobadas.
- `OrderOwnershipIntegrationTests`: 17 pruebas aprobadas.
- `BusinessApiIntegrationTests`: 39 pruebas aprobadas.
- `SessionAuthenticationTests`: 37 pruebas aprobadas.
- `PersistentSessionIntegrationTests`: 23 pruebas aprobadas.
- `SellerDispatchTests`: 33 pruebas aprobadas.

Total: 204 pruebas aprobadas. Se corrigieron fixtures de cuentas de checkout para usar
un hash BCrypt válido y se repitieron las pruebas afectadas. `git diff --check` sin errores.
No se modificó frontend, por lo que no se requirió su build.
