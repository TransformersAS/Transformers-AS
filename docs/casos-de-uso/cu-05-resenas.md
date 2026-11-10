# CU-05: publicar y atender reseñas de productos comprados

El comprador reseña un producto desde **Mis pedidos → Ver detalle**, cuando el
pedido está **Entregado**. El backend vuelve a verificar identidad, propiedad de
la compra, producto incluido y estado; ocultar un botón no sustituye estos controles.

La calificación es obligatoria, de 1 a 5. Comentario e imágenes son opcionales:
se puede publicar únicamente la calificación. El comentario y las respuestas
admiten hasta 2000 caracteres. Los errores conservan los datos válidos del formulario.

## Qué se implementó

- Una reseña vigente por comprador, pedido y producto. Otra compra entregada del
  mismo producto puede reseñarse por separado. Las solicitudes concurrentes no
  duplican el registro: bloqueo transaccional y restricción única en MySQL.
- **Mis compras → Mis reseñas** permite consultar, modificar y eliminar las propias.
  La eliminación es lógica; las imágenes se eliminan. Si se vuelve a publicar para
  esa compra, se reutiliza el registro y se retira la respuesta anterior.
- **Mi negocio → Reseñas recibidas** permite filtrar por producto, calificación y
  existencia de respuesta. Solo aparecen reseñas de la tienda autorizada.
- El vendedor consulta la compra asociada sin recibir dirección, teléfono ni
  credenciales. Publica, edita o elimina su propia respuesta; un cambio de dueño
  de tienda no autoriza editar la respuesta escrita por el dueño anterior.
- El detalle público muestra reseñas, imágenes, respuesta, promedio y distribución
  de estrellas. El catálogo recibe promedio y cantidad reales; ya no presume cinco
  estrellas sin reseñas. Las valoraciones se calculan con reseñas vigentes y visibles.
- Los botones de reporte usan el CU-20 existente, identificando exactamente
  `RESENA` o `RESPUESTA_RESENA`. Ocultar una reseña por moderación retira su contenido,
  imágenes y calificación del resultado público. Una respuesta puede ocultarse
  independientemente. El borrado lógico conserva el texto para revisión de moderación.

## Imágenes y privacidad

Se admiten hasta **3 imágenes JPG o PNG**, máximo **5 MB por archivo**. El backend
valida el contenido real mediante `ImageValidator`, no solo el nombre o MIME
declarado por el navegador. Rechaza formatos no permitidos y archivos corruptos.
Redimensiona a un máximo de 1600 píxeles por lado manteniendo proporción y vuelve
a codificar para retirar metadatos. Las imágenes y la reseña se guardan en una
transacción; un fallo no deja una publicación incompleta.

Las imágenes se cargan de forma diferida y usan ETag con revalidación. No hay
proceso periódico para recalcular valoraciones: se consultan agregados SQL cuando
se necesitan. Las respuestas públicas omiten el ID de compra y datos personales
del comprador. Los endpoints de escritura conservan protección de sesión y CSRF.

## Archivos principales

| Archivo | Responsabilidad |
| --- | --- |
| `backend/demo/src/main/resources/db/migration/V43__product_reviews.sql` | Tablas, índices, relaciones y unicidad. |
| `backend/demo/src/main/java/com/transformersas/marketplace/reviews/application/ReviewService.java` | Elegibilidad, permisos, transacciones, consultas y valoraciones. |
| `backend/demo/src/main/java/com/transformersas/marketplace/reviews/application/ReviewImages.java` | Validación y transformación de imágenes. |
| `backend/demo/src/main/java/com/transformersas/marketplace/reviews/infrastructure/web/controller/ReviewController.java` | API pública, de comprador y de vendedor. |
| `backend/demo/src/main/java/com/transformersas/marketplace/reviews/infrastructure/ReviewContentConfiguration.java` | Adaptadores al reporte y moderación existentes. |
| `backend/demo/src/main/java/com/transformersas/marketplace/shared/SecurityConfiguration.java` | Acceso público de lectura y rutas protegidas. |
| `backend/demo/src/main/java/com/transformersas/marketplace/product/` | Promedio y cantidad en listado y detalle del catálogo. |
| `frontend/src/app/resenas-favoritos/services/resenas.service.ts` | Cliente HTTP y envío JSON o multipart. |
| `frontend/src/app/resenas-favoritos/components/editor-resena.component.ts` | Formulario del comprador y validaciones. |
| `frontend/src/app/resenas-favoritos/components/mis-resenas.component.ts` | Panel de comprador/vendedor y administración. |
| `frontend/src/app/resenas-favoritos/components/resenas-producto.component.ts` | Lectura pública y reporte. |
| `frontend/src/app/pedidos/components/mis-pedidos.component.html` | Inicio de reseña en compras entregadas. |
| `frontend/src/app/app.component.*` | Menús por rol y detalle público. |

## API para comprobar con Postman

| Método y ruta | Uso |
| --- | --- |
| `GET /api/reviews/eligibility?orderId=7&productId=2` | Comprueba que la compra propia permite reseñar. Sustituir IDs por datos reales. |
| `POST /api/reviews` | Publica con `orderId`, `productId`, `rating`, `comment` y `keepImageIds: []`. |
| `GET /api/reviews/mine` | Consulta las reseñas de la sesión compradora. |
| `PUT /api/reviews/{id}` / `DELETE /api/reviews/{id}` | Modifica o elimina una reseña propia. |
| `GET /api/reviews/products/{id}` / `GET /api/reviews/products/{id}/summary` | Lista pública y valoración del producto. |
| `GET /api/seller/reviews` | Lista de la tienda; admite `productId`, `rating`, `answered` y `page`. |
| `GET /api/seller/reviews/{id}/purchase` | Compra mínima asociada, únicamente para la tienda autorizada. |
| `PUT /api/seller/reviews/{id}/reply` / `DELETE /api/seller/reviews/{id}/reply` | Publica o modifica con `{"text":"Gracias por tu opinión"}`, o elimina la respuesta. |

Las solicitudes protegidas requieren la cookie de sesión del rol correspondiente
y el encabezado CSRF obtenido por el mecanismo de autenticación existente. Para
adjuntar archivos, POST/PUT acepta multipart con parte `review` de tipo
`application/json` y partes `images`. No enviar un token de otro comprador para
intentar atribuirle una reseña: el backend identifica al autor mediante la sesión.

## Pruebas automáticas

Requiere Docker iniciado, Java 21, Maven, Node y dependencias del frontend instaladas.
Desde la raíz, usando Java 21 en la terminal:

```powershell
node scripts/probar-cu05.mjs
```

Ejecuta backend con MySQL aislado, compilación del frontend y recorridos de Chrome.
Guarda logs, resultado y cobertura en `artifacts/cu05/<fecha>/`. No lee `.env`
ni modifica la base de datos de la demostración. Se detiene si una etapa falla.

También se pueden ejecutar por separado:

```powershell
cd backend/demo
mvn.cmd -Pcu05-coverage clean verify
```

```powershell
cd frontend
npx.cmd playwright test e2e/resenas.spec.ts --project=chrome --workers=1
```

Para ver los recorridos con Chrome visible y 700 ms entre acciones:

```powershell
cd frontend
npm.cmd run demo:cu05
```

La demostración utiliza API controlada y puerto 4438: muestra la automatización de
la interfaz y no crea reseñas en tu MySQL local. La integración real con MySQL,
sesiones, archivos y permisos se verifica mediante las pruebas Java.

### Cobertura

El perfil `cu05-coverage` exige **más del 95 % de líneas** (`0.9501`) por paquete
de `com.transformersas.marketplace.reviews.*`, incluidos controladores y adaptadores.
El `verify` general conserva el umbral global existente y añade esta regla para
reseñas. No se excluye código nuevo de reseñas para elevar el porcentaje.

Informe: `backend/demo/target/site/jacoco-cu05/index.html`.
La cobertura de ramas se informa separadamente; el porcentaje Java no mide HTML,
TypeScript ni demuestra por sí solo el cumplimiento de todos los requisitos.

### Evidencia de la revisión del 9 de noviembre de 2026

- Perfil `cu05-coverage`: 23 pruebas, sin fallos ni errores; `verify` exitoso.
- Líneas Java: aplicación de reseñas **99,25 %** (132 cubiertas, 1 sin cubrir),
  controladores **100 %** (26/26) y adaptadores de moderación **100 %** (17/17).
- Interfaz: siete escenarios ejecutados en Chrome, Edge y vista móvil,
  **21 ejecuciones exitosas**.
- Regresión Chrome de reseñas, catálogo, favoritos y comprobantes:
  **21 pruebas exitosas**; después se añadió y verificó también el escenario público.
- Compilación de producción de Angular: exitosa.
- Regresión general: 1754 pruebas en los informes actualizados, 0 fallos,
  0 errores y 1 prueba de carga CU-04 omitida por su condición optativa.
  La primera ejecución general detectó tres fallos del sustituto de reseñas de
  `SupportApiIntegrationTests`: el adaptador real nuevo tenía prioridad sobre
  su proveedor ficticio. Se hizo explícita la prioridad del sustituto únicamente
  en esa prueba y se repitieron sus 39 escenarios, todos exitosos. Las aserciones
  de privacidad, destinatarios y recuperación permanecen intactas.
- Cobertura global de líneas acumulada: **98,14 %** (9465/9644). Tras repetir
  soporte, `mvn.cmd -DskipTests verify` generó el artefacto y aprobó todas las reglas
  de JaCoCo con los datos de las ejecuciones anteriores. Este último comando
  comprueba los umbrales; no constituye otra ejecución completa de las pruebas.
- Evidencia local: `artifacts/cu05/revision-2026-11-09/resultado.json`, logs y XML
  JaCoCo. El directorio de artefactos está excluido de Git.

Estos resultados corresponden al código de esta revisión. Cambios posteriores
requieren volver a ejecutar las verificaciones; no garantizan por adelantado el
resultado de otra ejecución de GitHub Actions.

| Requisito | Evidencia |
| --- | --- |
| RF-022 | Compra ajena, producto no comprado y estados distintos de Entregado rechazados en integración. |
| RF-023/196 | Publicación solo estrellas, edición propia, eliminación, duplicados y concurrencia; recorrido visual. |
| RF-024 / RNF-007 | Multipart, bytes falsos, límites, conservación/retirada de imágenes y redimensionamiento. |
| RF-025/099 | Promedio y distribución después de cambios, borrado y moderación; catálogo y detalle. |
| RF-095/096 | Filtros por tienda y consulta mínima de compra; otra tienda recibe rechazo. |
| RF-097 | Crear, editar y borrar respuesta; denegación a otro actor. |
| RF-098 | Reporte con identificador exacto e integración con ocultamiento. |
| RNF-003/010 | Sesión, rol, CSRF, aislamiento entre cuentas y ausencia de datos privados en respuestas públicas. |
| RNF-027 | Errores de campo, imagen inválida y reintento conservando comentario. |
| RNF-047 | Imágenes redimensionadas, carga diferida, ETag y agregación bajo demanda. |

Clases: `ReviewsIntegrationTests` y `ReviewImagesTests`.
Interfaz: `frontend/e2e/resenas.spec.ts`.
La regresión relacionada incluye `ProductDetailIntegrationTests` y
`ContentReportIntegrationTests`, junto con `SupportApiIntegrationTests` para la
compatibilidad del proveedor controlado de contenido con soporte. El perfil
específico del CU-05 no ejecuta toda la suite del repositorio.

## Cómo demostrarlo manualmente

En la base local `marketplace-pruebas`, el 9 de noviembre se reconcilió la antigua
V40 de push con V42 después de comprobar que el checksum y la estructura coincidían.
Se creó un respaldo completo en `artifacts/cu05/respaldo-flyway-20261109-185030/`.
Después se aplicaron V40 de costos, V41 de suspensiones y V43 de reseñas usando
`out-of-order` temporalmente; el arranque posterior volvió a la configuración normal.
Los conteos de usuarios, productos, pedidos, líneas, pagos, notificaciones y
suscripciones se conservaron. Backend, frontend, MySQL y RabbitMQ quedaron saludables;
liveness, readiness y la API pública de reseñas respondieron HTTP 200. La advertencia
del paso siguiente sigue aplicando a otras bases que conserven el historial antiguo.

1. Actualizar los contenedores con el código y migraciones actuales. Si la base
   local conserva una migración antigua V40 de push, revisar primero su historial
   según la guía de notificaciones; no borrar volúmenes ni ejecutar reparaciones a ciegas.
2. Entrar con un comprador que tenga un pedido realmente Entregado. Abrir el
   detalle del pedido y pulsar **Reseñar producto**. Un pedido confirmado o cancelado
   no es elegible; no cambiar su estado por SQL para presentar una compra como entregada.
3. Publicar 5 estrellas sin comentario. Consultar el detalle público y comprobar
   el promedio. Después modificar a 3 estrellas desde **Mis reseñas** y comprobarlo de nuevo.
4. Adjuntar un JPG/PNG válido; intentar después un archivo no permitido para ver el
   error. Verificar que un comprador diferente no puede administrar esa reseña.
5. Entrar como vendedor de esa tienda, abrir **Reseñas recibidas**, filtrar, consultar
   la compra asociada y crear/editar/eliminar una respuesta.
6. Eliminar la reseña como comprador y verificar que deja de aportar al promedio.

### Consultas de lectura para la sustentación

Últimas diez reseñas, incluyendo eliminadas:

```sql
SELECT r.id AS resena_id, u.email AS comprador, p.name AS producto,
       r.order_id AS pedido_id, o.status AS estado_pedido,
       r.rating AS estrellas, r.comment AS comentario,
       r.deleted AS eliminada, x.text AS respuesta_vendedor,
       r.created_at, r.updated_at
FROM product_reviews r
JOIN user_accounts u ON u.id = r.account_id
JOIN products p ON p.id = r.product_id
JOIN orders o ON o.id = r.order_id
LEFT JOIN product_review_replies x ON x.review_id = r.id
ORDER BY r.id DESC
LIMIT 10;
```

Valoración vigente, omitiendo reseñas ocultadas por moderación:

```sql
SELECT p.id, p.name AS producto, COUNT(r.id) AS cantidad,
       ROUND(AVG(r.rating), 2) AS promedio
FROM products p
LEFT JOIN product_reviews r ON r.product_id = p.id
  AND r.deleted = FALSE
  AND NOT EXISTS (
    SELECT 1 FROM content_moderation_state m
    WHERE m.content_type = 'RESENA' AND m.content_id = CAST(r.id AS CHAR)
      AND m.state <> 'VISIBLE'
  )
GROUP BY p.id, p.name
ORDER BY p.id;
```

La consulta de sustentación muestra correo por ser una consulta administrativa
local; la API pública de reseñas no lo expone. Consultar también la API permite
verificar el backend HTTP; una consulta SQL verifica la persistencia.
