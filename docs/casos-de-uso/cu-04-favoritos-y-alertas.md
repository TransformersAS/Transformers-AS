# CU-04: favoritos y seguimiento de productos

## Comportamiento

El comprador autenticado puede guardar o retirar un producto usando el corazón del
catálogo. El corazón naranja indica un favorito. La información se guarda en MySQL
por cuenta y se recupera después de recargar o iniciar sesión de nuevo; no se usa
`localStorage` ni `sessionStorage`.

Desde la bolita naranja inferior izquierda se abre **Mis favoritos y alertas**.
La lista permite actualizar los datos, ordenar los productos cargados por nombre,
precio o fecha de guardado, cargar páginas adicionales, ver el detalle, agregar al
carrito, comparar y copiar un enlace al producto. El backend aplica las reglas
vigentes del carrito. Comparar conserva el máximo de tres productos y la validación
de categoría del catálogo existente.

El precio y las existencias se consultan del producto actual, no de una copia del
momento en que se guardó. Las publicaciones pausadas permanecen visibles con su
estado. Las publicaciones retiradas, borradores y contenido oculto por moderación
se presentan como no disponibles, sin exponer sus datos actuales.

## Alertas

- **Precio:** objetivo positivo, hasta dos decimales. Si el precio actual ya es
  igual o menor, se informa de inmediato y no se crea una alerta activa.
- **Disponibilidad:** se selecciona una variante existente y agotada. El aumento
  de stock de otra variante o del producto general no cumple esta condición.
- Se pueden consultar, modificar, pausar, reactivar y eliminar las alertas propias.
  La modificación de disponibilidad permite seleccionar otra variante agotada.
- Cada alerta activa se cumple una vez. Después queda `TRIGGERED` y se puede
  reactivar cuando la condición deje de cumplirse. La reactivación incrementa su
  revisión, por lo que un nuevo cumplimiento es un evento diferente.
- No se permiten dos alertas activas o pausadas del mismo comprador para el mismo
  producto, tipo y variante. Límites por cuenta: 500 favoritos y 500 alertas no
  eliminadas. Estos límites acotan consultas y almacenamiento.

| Estado persistido | Significado |
| --- | --- |
| `ACTIVE` | La condición se evalúa. |
| `PAUSED` | Se conserva, sin generar avisos. |
| `TRIGGERED` | Se cumplió; no vuelve a avisar sin reactivación o modificación. |
| `DELETED` | Se excluye de la lista y de la evaluación. |

Si al modificar una alerta de precio su condición ya se cumple, queda sin actividad
y se informa inmediatamente. Una publicación inactiva o una variante que ya no
existe no genera avisos. El nombre de la variante se guarda porque el modelo actual
no tiene un identificador independiente; si se renombra, se debe editar la alerta.
Reordenar variantes conserva el seguimiento por nombre.

## Evaluación y notificaciones

Un barrido configurable procesa lotes de hasta 100 alertas activas. Usa un cursor
para recorrerlas sin quedarse siempre en el primer lote. Cada evaluación tiene su
propia transacción, por lo que el error de una alerta no impide evaluar las demás.

Dentro de esa transacción se bloquea la alerta, se verifica el producto actual y,
si se cumple, se crea una notificación interna y se cambia el estado a `TRIGGERED`.
La clave `product-alert-{UUID}-{revision}` y la restricción única de notificaciones
evitan duplicados. Si hay rollback, no se confirma ninguno de esos cambios.

El módulo existente despacha la notificación externa **después del commit**, fuera
del hilo de la solicitud y mediante un ejecutor acotado. Un error del proveedor
conserva la notificación interna y registra el resultado externo para diagnóstico
y recuperación. No se incluye un nuevo proveedor ni un reintento automático.

La bandeja se abre desde el icono de mensajes del encabezado. Mientras está abierta,
consulta novedades cada dos segundos y ofrece **Ver producto** para una alerta.
El envío externo puede usar el simulador existente o el adaptador HTTP configurado;
no significa que se esté enviando un correo real por defecto.

```mermaid
sequenceDiagram
    actor Comprador
    participant Web as Angular
    participant API as Favoritos y alertas
    participant DB as MySQL
    participant Evaluador as Barrido de alertas
    participant Avisos as Módulo de notificaciones
    Comprador->>Web: Guardar producto / configurar alerta
    Web->>API: Solicitud con sesión y CSRF
    API->>DB: Guardar datos del comprador
    Evaluador->>API: Evaluar alerta
    API->>DB: Bloquear alerta y consultar producto
    API->>Avisos: Publicar aviso con clave única
    Avisos->>DB: Guardar notificación interna
    API->>DB: Marcar alerta cumplida y confirmar transacción
    Avisos->>Avisos: Solicitar aviso externo después del commit
    Web->>Avisos: Consultar bandeja
    Avisos-->>Web: Aviso y referencia al producto
```

## Contratos y almacenamiento

Todos los endpoints siguientes requieren el rol activo `COMPRADOR`. La cuenta se
obtiene de la sesión; no se acepta un `user_id` elegido por el navegador. Las
escrituras requieren CSRF. Operar sobre una alerta ajena devuelve 404.

| Método y ruta | Función |
| --- | --- |
| `GET /api/favorites?page=0` | Página de hasta 50 favoritos con datos actuales. |
| `GET /api/favorites/ids` | Identificadores para los corazones del catálogo. |
| `PUT /api/favorites/{productId}` | Guardar, de forma idempotente. |
| `DELETE /api/favorites/{productId}` | Retirar el favorito de la cuenta. |
| `GET /api/favorites/alerts` | Consultar las alertas propias no eliminadas. |
| `POST /api/favorites/alerts` | Crear una condición. |
| `PUT /api/favorites/alerts/{id}` | Modificar la condición de una alerta propia. |
| `PATCH /api/favorites/alerts/{id}` | Pausar o reactivar mediante `status`. |
| `DELETE /api/favorites/alerts/{id}` | Eliminar lógicamente. |

Ejemplo de alerta de precio:

```json
{"productId": 1, "kind": "PRICE", "variantName": null, "targetPrice": 25000}
```

Ejemplo de disponibilidad; el nombre debe coincidir con una variante agotada:

```json
{"productId": 1, "kind": "AVAILABILITY", "variantName": "Talla M", "targetPrice": null}
```

La migración `V39__favorites_and_product_alerts.sql` crea `buyer_favorites` y
`product_alerts`. Las claves foráneas mantienen las referencias a productos y
cuentas; la eliminación física de esos recursos limpia las referencias asociadas.
Las notificaciones se guardan en la tabla existente `notifications`.

Para consultar evidencia de prueba:

```sql
SELECT f.account_id, f.product_id, p.name, p.price, p.stock, p.status, f.created_at
FROM buyer_favorites f JOIN products p ON p.id = f.product_id
ORDER BY f.created_at DESC;

SELECT a.id, a.account_id, p.name AS producto, a.kind, a.variant_name,
       a.target_price, a.status, a.revision
FROM product_alerts a JOIN products p ON p.id = a.product_id
ORDER BY a.created_at DESC;

SELECT id, recipient_id, title, reference_id AS producto_id,
       event_key, external_status, created_at
FROM notifications WHERE type = 'PRODUCT_ALERT'
ORDER BY created_at DESC;
```

Estas consultas son para administrar la base de pruebas. El frontend solo debe
consultar sus endpoints autorizados; no tiene acceso a los datos de otras cuentas.

## Ejecución y configuración

En esta instalación Docker, reconstruir backend y frontend:

```powershell
docker compose -p marketplace-pruebas up -d --build backend frontend
docker compose -p marketplace-pruebas ps
```

Esperar a que los servicios estén saludables antes de probar. Si se desea conservar
el perfil de pagos, usar también `-f compose.yaml -f compose.pagos-demo.yaml`.
Flyway aplica la migración sin borrar los datos existentes. Esta entrega no modifica
`.env` ni activa un proveedor de notificaciones de producción.

Propiedades del barrido, configurables con Spring:

```properties
favorites.alerts.enabled=true
favorites.alerts.interval=1000
favorites.alerts.initial-delay=1000
```

Los intervalos anteriores están en milisegundos. Las pruebas desactivan el barrido
automático y llaman a la evaluación de forma controlada. El flujo de notificación
externa sigue la configuración `notifications.provider` existente, con simulación
de éxito, rechazo e indisponibilidad y límite de espera del adaptador HTTP.

## Trazabilidad y comprobación

| Requisitos | Implementación y evidencia |
| --- | --- |
| RF-011, RF-012 | Corazones, lista por comprador, orden de productos cargados y páginas; claves únicas y aislamiento. |
| RF-013 | Consulta del precio, stock y estado actuales; manejo de publicaciones no disponibles. |
| RF-014 | Carrito, comparación existente y enlace compartible `?producto={id}`. |
| RF-133, RF-134 | Validación de precio y variante agotada; condición ya cumplida. |
| RF-135 | Listado, edición de precio o variante, pausa, reactivación y eliminación. |
| RF-136, RF-138 | Barrido, transacciones independientes, revisión y deduplicación; exclusión de alertas y publicaciones inactivas. |
| RF-137 | Aviso interno con referencia al producto y despacho externo después del commit. |
| RNF-010 | Roles, sesión, CSRF, consultas por propietario y ausencia de datos personales en el aviso de producto. |
| RNF-014, RNF-037, RNF-042, RNF-045 | Simulador, pruebas HTTP del proveedor y conservación del aviso ante fallos; no bloquear el catálogo por fallos auxiliares. |
| RNF-018 | Medición local con 100 sesiones, 900 operaciones de favoritos y umbral P95 de dos segundos. No incluye red, proxy ni navegador. |
| RNF-020 | Prueba de actualización de bandeja dentro de cinco segundos con API controlada; la latencia completa bajo carga requiere medición en el entorno final. |

### Pruebas reproducibles

Desde `backend/demo`, con Java 21, Maven y Docker:

```powershell
mvn.cmd '-Dtest=FavoritesIntegrationTests,ProductAlertSchedulerTests,NotificationInboxTests,NotificationsHttpIntegrationTests' test
```

La medición de rendimiento se ejecuta aparte, sin compilaciones ni navegadores
de prueba compitiendo por CPU y manteniendo el pool de diez
conexiones. No se activa en cada ejecución funcional de CI:

```powershell
mvn.cmd '-Dtest=FavoritesConcurrencyTests' '-Dcu04.performance=true' test
```

Desde `frontend`:

```powershell
npm.cmd run build
npx.cmd playwright test --project=chrome --workers=2 --reporter=list
```

La prueba de concurrencia usa 100 sesiones diferentes y MySQL Testcontainers;
repite guardar, listar y retirar favoritos. No utiliza la base de datos de la demo.
Los tests de Chrome controlan las respuestas de API y prueban interacción y
presentación; no equivalen a un recorrido completo contra el backend real.

Para una demostración manual: guardar un producto, recargar y consultar favoritos;
crear una alerta por debajo de su precio; cambiar el precio desde la cuenta
vendedora; abrir las notificaciones del comprador y seguir el enlace al producto.
Repetir con una variante agotada, reponerla desde el inventario y comprobar el
aviso. Verificar también pausa, eliminación y publicación inactiva.

El objetivo temporal depende del volumen de alertas, de los lotes pendientes y de
los recursos de la máquina. Las comprobaciones locales no certifican el SLA en
producción ni una prueba de carga completa de carrito, perfil o direcciones.

### Resultados locales del 2 de noviembre de 2026

| Verificación | Resultado |
| --- | --- |
| Backend: favoritos, ciclo de alertas, propiedad, CSRF, rollback y barrido | 15 pruebas aprobadas. |
| Regresión de bandeja y notificaciones HTTP (éxito, fallo, timeout) | 12 pruebas aprobadas. |
| Regresión completa de Chrome | 97 pruebas aprobadas. |
| CU-04 en Chrome después de los ajustes finales | 5 aprobadas: enlace compartido, favoritos persistentes y retiro, precio ya cumplido, variante agotada y notificación con enlace. |
| Compilación de producción de Angular | Correcta. |
| Carga aislada: 100 sesiones, 900 operaciones | Una prueba aprobada; P95 de 956,41 ms. |

La ejecución de carga simultánea con compilación y regresión de navegador superó
el umbral: P95 de 3324,44 ms. No se cambió el límite de dos segundos para hacerla
pasar; se separó la ejecución de rendimiento de las pruebas funcionales. El primer
experimento había dado 699,07 ms. Esta variación confirma que la evidencia es local
y que se debe medir de nuevo en el entorno de entrega con carga representativa.
