# Perfil para demostrar el vencimiento de una compra

La duración de las reservas se configura mediante `checkout.reservation-ttl`.
La ejecución normal mantiene diez minutos; el perfil Spring `pagos-demo` usa
60 segundos por defecto. No se necesita volver a editar Java para cambiarla.
El checkout de Mercado Pago toma su vencimiento de esas mismas reservas.

## Docker en esta instalación

Desde la raíz del proyecto:

```powershell
docker compose -p marketplace-pruebas -f compose.yaml -f compose.pagos-demo.yaml up -d --build backend
```

El complemento activa únicamente el perfil `pagos-demo` en el backend, reutilizando
la base y los contenedores del proyecto. No activa el perfil independiente `demo`
ni sus migraciones académicas. Si se necesitan otros perfiles, se deben indicar
explícitamente junto con `pagos-demo` en el complemento.

Para elegir otra duración, por ejemplo dos minutos:

```powershell
$env:CHECKOUT_RESERVATION_TTL = '2m'
docker compose -p marketplace-pruebas -f compose.yaml -f compose.pagos-demo.yaml up -d --force-recreate backend
```

También se puede definir `CHECKOUT_RESERVATION_TTL=2m` en el `.env` local.
La configuración de sesión de PowerShell tiene prioridad sobre `.env`.
La duración debe ser positiva. Cambiarla exige recrear el contenedor, pero no
recompilar si la imagen ya contiene esta implementación.

Para volver a la ejecución normal, quitar la variable de PowerShell si se usó,
y usar solo el archivo principal:

```powershell
Remove-Item Env:CHECKOUT_RESERVATION_TTL -ErrorAction SilentlyContinue
docker compose -p marketplace-pruebas -f compose.yaml up -d --force-recreate backend
```

## Sin Docker

Desde `backend/demo`, con Java 21 y la conexión a MySQL configurada:

```powershell
mvn.cmd spring-boot:run '-Dspring-boot.run.profiles=pagos-demo'
```

La variable de entorno `CHECKOUT_RESERVATION_TTL` permite ajustar el plazo también
en esta ejecución.

## Cómo demostrarlo

1. Activar el perfil y crear un checkout nuevo con productos de una sola tienda.
2. Consultar sus fechas:

```sql
SELECT id, status, created_at, expires_at,
       TIMESTAMPDIFF(SECOND, created_at, expires_at) AS segundos_reserva
FROM payment_checkouts
ORDER BY created_at DESC
LIMIT 5;
```

3. Cambiar un dato de la compra e intentar iniciar otra: mientras esté vigente,
   el checkout anterior debe bloquear la creación de otro con datos diferentes.
4. Dejar vencer la reserva, e iniciar otra compra: la anterior ya no debe bloquearla.

Las reservas ya creadas no se acortan al activar el perfil. Vencer no implica que
el estado `PENDING` cambie a `REJECTED`: son conceptos diferentes. No se debe
modificar la base manualmente para obtener el resultado de la demostración.

Un minuto está pensado para probar el vencimiento, y puede ser insuficiente para
completar los formularios de pago. Para demostrar una aprobación, utilizar un plazo
mayor. Una aprobación después de vencer el inventario puede requerir revisión.
