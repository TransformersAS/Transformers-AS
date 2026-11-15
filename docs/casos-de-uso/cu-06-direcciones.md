# CU-06: configurar direcciones de entrega

El comprador administra sus direcciones desde **Mis direcciones**, en el menú de su cuenta. Durante una compra también puede seleccionar, registrar o editar una dirección sin salir del checkout. Se utilizan los datos reales guardados de destinatario, teléfono y ubicación; calcular el total ya no crea direcciones repetidas ni introduce contactos ficticios.

## Selector compacto en checkout

El checkout muestra inicialmente un resumen de la dirección seleccionada (predeterminada cuando corresponde), en lugar de todas las tarjetas de administración. **Elegir o cambiar dirección** despliega las opciones, la búsqueda por destinatario/dirección/ciudad/departamento y las acciones de nueva dirección y edición. La lista tiene desplazamiento interno limitado a 18 rem para no alejar envío y pago.

Seleccionar otra dirección cierra el desplegable, actualiza el resumen y devuelve el foco al botón de selección. La dirección elegida sigue enviándose por su ID al cálculo del total. Guardar una dirección nueva también cierra el selector; si no existen direcciones se abre para permitir registrarla. Los errores conservan el formulario y cerrar/reabrir el selector no descarta el borrador.

La pantalla independiente **Mis direcciones** conserva la lista completa. No se cambian permisos, persistencia, reglas de eliminación ni la relación entre dirección y pedido. La evaluación de usabilidad con participantes debe medir por separado si esta reducción de desplazamiento facilita la tarea; las pruebas de interfaz no prueban satisfacción.

## Requisitos implementados

Validación del selector compacto: compilación de frontend correcta y **40 pruebas Playwright aprobadas** entre `direcciones.spec.ts`, `mercadopago.spec.ts` y `compra-sesion.spec.ts`. Se comprueba búsqueda y selección con 42 direcciones en anchos de 1280 y 390 píxeles, cierre automático, recuperación del foco y envío del ID elegido al cálculo. Estas pruebas usan API controlada.

| Requisito | Comportamiento y verificación |
| --- | --- |
| RF-186 | Listar y registrar direcciones propias; persistencia después de recargar. |
| RF-187 | Editar destinatario, calle, ciudad, departamento, código postal opcional y teléfono; errores junto a los campos y conservación del formulario. |
| RF-188 | Confirmación de eliminación; rechazo si hay pedido en proceso o compra de pago pendiente vigente; conservación de las direcciones históricas del pedido. |
| RF-189 | Una predeterminada por comprador y selección de dirección guardada durante la compra. |
| RNF-003/010 | Sesión de comprador, CSRF en escrituras y control de propiedad en backend; otra cuenta no lista ni modifica direcciones ajenas. No se guardan direcciones en localStorage. |
| RNF-027 | Validación en interfaz y servidor, mensajes por campo y reintento sin perder datos válidos. |
| RNF-018 | Medición local opcional con 100 compradores concurrentes y límite p95 de 2.000 ms. Alcance descrito abajo. |

La primera dirección se marca automáticamente como predeterminada. Al cambiarla se retira la marca de la anterior en la misma transacción. Si se elimina la predeterminada, se selecciona la dirección restante de menor ID; si no quedan direcciones, no hay predeterminada.

La migración **V48** conserva las direcciones existentes y marca como predeterminada la más antigua de cada cuenta. Una columna generada y un índice único impiden dos predeterminadas activas de la misma cuenta, incluso ante concurrencia. Las operaciones que cambian esa condición bloquean la fila de la cuenta durante la transacción.

La eliminación es lógica (`deleted=true`): la dirección desaparece de las consultas normales y del checkout, pero se conserva para no romper las referencias históricas. Los pedidos mantienen su propia copia de los datos de entrega; editar o eliminar la dirección guardada no cambia esa copia.

Se permite eliminar cuando los pedidos asociados están en `DELIVERED`, `CANCELLED` o `RETURNED_TO_SELLER`; otros estados impiden eliminar. También se protege una dirección usada por un checkout sin pedido y sin cancelación: `REVIEW`, o `PENDING`, `STARTING` y `REJECTED` con reserva vigente. Esto evita retirar la dirección mientras todavía puede confirmarse un pago.

## Archivos principales

| Archivo, relativo a la raíz | Función |
| --- | --- |
| `backend/demo/src/main/resources/db/migration/V48__manage_delivery_addresses.sql` | Borrado lógico, predeterminada, índices y actualización de registros anteriores. |
| `backend/demo/src/main/java/com/transformersas/marketplace/address/Address.java` | Modelo y exclusión de direcciones eliminadas. |
| `backend/demo/src/main/java/com/transformersas/marketplace/address/AddressRepository.java` | Consultas por propietario y bloqueo de la dirección durante operaciones de escritura. |
| `backend/demo/src/main/java/com/transformersas/marketplace/address/AddressService.java` | Propiedad, transacciones, predeterminada y restricciones de eliminación. |
| `backend/demo/src/main/java/com/transformersas/marketplace/address/AddressController.java` | API autenticada. |
| `backend/demo/src/main/java/com/transformersas/marketplace/address/dto/` | Validación de solicitudes y respuesta con `defaultAddress`. |
| `frontend/src/app/direcciones/` | Servicio HTTP y componente reutilizado en cuenta y compra. |
| `frontend/src/app/app.component.ts` y `app.component.html` | Acceso desde el menú e integración con selección y cálculo de compra; descarta respuestas de cálculo anteriores a un cambio de dirección. |
| `backend/demo/src/test/java/com/transformersas/marketplace/address/` | Integración MySQL, migración desde V47 y concurrencia. |
| `frontend/e2e/direcciones.spec.ts` | Pruebas de interfaz con API controlada. |
| `frontend/e2e/mercadopago.spec.ts` y `compra-sesion.spec.ts` | Regresión de pago y separación de sesiones usando direcciones guardadas. |
| `scripts/probar-cu06.mjs` | Ejecución de backend, cobertura, compilación e interfaz; conserva evidencias. |
| `frontend/playwright.cu06-demo.config.ts` | Demostración visible y lenta. |
| `backend/demo/pom.xml` | Perfil de cobertura y umbral de líneas superior al 95 % para los paquetes de direcciones. |

## API

Todas las rutas requieren una sesión con rol activo de comprador. Las escrituras requieren el token CSRF usado por el resto del sistema. El propietario se obtiene de la sesión, no del cuerpo enviado por el cliente.

| Método y ruta | Resultado |
| --- | --- |
| `GET /api/addresses` | 200, direcciones propias activas, predeterminada primero. |
| `POST /api/addresses` | 201, dirección registrada. |
| `PUT /api/addresses/{id}` | 200, dirección actualizada. |
| `PUT /api/addresses/{id}/default` | 200, predeterminada seleccionada; repetir no duplica registros. |
| `DELETE /api/addresses/{id}` | 204 si se permite; 409 si tiene una operación pendiente. |

Una dirección inexistente, eliminada o ajena produce 404 sin revelar a quién pertenece. Un formulario inválido produce 400. Se exige destinatario, calle, ciudad, departamento y teléfono de 7 a 15 dígitos; el código postal es opcional. También se limitan las longitudes en backend.

## Arranque y prueba manual

Desde la raíz, con Docker Desktop activo:

```powershell
docker compose -p marketplace-pruebas -f compose.yaml up -d --build
```

Si normalmente usas archivos adicionales de notificaciones o pagos, conserva los mismos `-f` de tu entorno. Flyway aplica V48 al iniciar el backend. No borres el volumen MySQL para aplicar esta migración.

1. Entra en `http://localhost:4300` con un comprador y abre **Mis direcciones**.
2. Crea una dirección completa: debe aparecer como predeterminada. Recarga y comprueba que permanece.
3. Crea otra dirección, edítala y márcala como predeterminada. Solo una debe conservar la marca.
4. Intenta guardar ciudad vacía o teléfono inválido: debe indicar el campo y conservar los demás datos.
5. Inicia una compra y selecciona una dirección guardada. Calcula varias veces: no deben aparecer direcciones duplicadas. Editar o cambiar la dirección obliga a recalcular el resumen.
6. Elimina una dirección sin pedidos activos: primero pide confirmación y luego deja de ofrecerla para compras futuras.
7. Intenta eliminar una dirección con pedido en proceso: debe informar el motivo y conservarla.
8. Con un pedido terminado, comprueba que su dirección histórica no cambia al editar o eliminar la dirección de la cuenta.
9. Inicia sesión con otro comprador: solo debe ver sus propias direcciones.

La gestión de direcciones funciona en localhost sin túnel HTTPS ni credenciales de Mercado Pago. Probar el cobro sigue requiriendo la configuración independiente del CU-03.

## Pruebas automáticas y cobertura

Requisitos: JDK 21 utilizado por Maven, Docker Desktop, Node y dependencias del frontend instaladas. Las pruebas de backend crean MySQL aislado con Testcontainers; no usan la base de datos local. Las pruebas de navegador de este CU controlan la API y tampoco modifican esa base.

Desde la raíz:

```powershell
node scripts/probar-cu06.mjs
```

Para incluir la medición con 100 compradores:

```powershell
node scripts/probar-cu06.mjs --rendimiento
```

Las evidencias quedan en `artifacts/cu06/<fecha>/`: registros por etapa, `resultado.json`, XML de JaCoCo y `cobertura.json`. Un error termina el proceso con código distinto de cero.

Para ejecutar únicamente backend, desde `backend/demo`:

```powershell
mvn.cmd -Pcu06-coverage clean verify
```

El informe está en `backend/demo/target/site/jacoco-cu06/index.html`. El perfil exige **más del 95 % de líneas Java** (`0.9501`) en los paquetes de direcciones, incluidos controladores y DTO. La verificación general conserva su umbral global y añade esta regla específica. Una ejecución del perfil no demuestra la cobertura de todo el backend ni mide cobertura TypeScript.

Para ver la demostración lenta, desde `frontend`:

```powershell
npm.cmd run demo:cu06
```

Abre Chrome, una prueba por vez y con 700 ms entre acciones; utiliza el puerto 4440 y API controlada. La demostración no sustituye la prueba de integración con MySQL.

La medición de rendimiento realiza 900 solicitudes con 100 compradores concurrentes, dos direcciones por cuenta y tres rondas de consulta, edición y cambio real de predeterminada. Excluye registro de cuentas y latencia de red externa; utiliza MockMvc y MySQL real aislado. Mantiene el límite de p95 de 2.000 ms. El resultado local observado fue **983,88 ms** después de reducir consultas y escrituras innecesarias. No certifica por sí solo el rendimiento de un despliegue productivo.

## Consultas de comprobación en MySQL

Ejecutar en la base del Marketplace con acceso administrativo autorizado. Incluyen datos personales: usar cuentas de demostración durante la sustentación.

Direcciones recientes, incluidas eliminadas:

```sql
    SELECT a.id, u.email AS comprador, a.recipient_name AS destinatario,
        a.street AS direccion, a.city AS ciudad, a.department AS departamento,
        a.phone AS telefono, a.is_default AS predeterminada, a.deleted AS eliminada
    FROM addresses a
    JOIN user_accounts u ON u.id = a.account_id
    ORDER BY a.id DESC
    LIMIT 10;
```

Esta consulta debe devolver cero filas:

```sql
SELECT account_id, COUNT(*) AS predeterminadas
FROM addresses
WHERE is_default = TRUE AND deleted = FALSE
GROUP BY account_id
HAVING COUNT(*) > 1;
```

Para comprobar qué pedidos impiden eliminar una dirección:

```sql
SELECT id, address_id, status, delivery_street
FROM orders
WHERE address_id = 1
ORDER BY id DESC;
```

Reemplazar `1` por el ID a verificar. `delivery_street` es la copia histórica del pedido y debe conservarse aunque se edite `addresses.street`.

## Resultado de la validacion local (14/11/2026)

- Perfil CU-06: 16 pruebas aprobadas; la prueba opcional de rendimiento se ejecuta por separado.
- JaCoCo: 59 de 59 lineas en `address` y 4 de 4 en `address/dto`: 100 % en ambos paquetes.
- Interfaz y regresion de compra en Chrome: 36 pruebas aprobadas.
- Direcciones en Edge y pantalla movil: 22 pruebas aprobadas.
- Regresion adicional de backend: 107 pruebas aprobadas (API, aislamiento, reservas, limites de dominio, creacion y detalle historico de pedidos).
- Compilacion del frontend: correcta.
- Rendimiento opcional: 100 compradores, 900 solicitudes y p95 de 983,88 ms; el limite se mantiene en 2.000 ms.
- Evidencias locales: `artifacts/cu06/validacion-2026-11-14/`. Son archivos generados; la guia y las pruebas fuente quedan versionadas.

Estos resultados corresponden al alcance indicado; no afirman que se haya ejecutado toda la suite global de CI.
