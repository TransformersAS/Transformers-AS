# CU-01: Explorar el catálogo y descubrir productos

Actor: comprador con sesión iniciada. El catálogo conserva la protección de sesión
del backend existente; no se modificaron sus permisos.

## Funcionalidad

- Búsqueda por palabras en nombre, descripción y categoría, sin distinguir
  mayúsculas ni tildes. Se aplica mientras se escribe; Enter mantiene el registro
  de búsquedas para recomendaciones.
- Filtro por categorías presentes en los productos y rango de precios inclusivo.
  Ropa y Moda se consideran una misma familia para filtrar y comparar.
- Ordenamiento por precio ascendente o descendente, nombre y más recientes
  (ID descendente). Relevancia conserva el orden entregado por la API; no supone
  un ranking inteligente nuevo.
- Contador de resultados, limpieza de filtros y mensajes para carga, error,
  falta de sesión, rango inválido y búsqueda sin coincidencias.
- Detalle consultado mediante `GET /api/products/{id}`: nombre, descripción,
  categoría, precio y existencias. Se puede agregar al carrito y comparar.
- Comparación de hasta tres productos de la misma familia de categoría, con
  precio, descripción y existencias. Cada selección se puede quitar o vaciar.
- El catálogo se consulta nuevamente al cambiar la sesión; al cerrarla se
  descartan el detalle y las selecciones de comparación.

El detalle utiliza las imágenes publicadas por el vendedor cuando existen; en su
ausencia muestra una imagen ilustrativa identificada como tal. Los filtros operan sobre el catálogo
descargado en el navegador; no hay paginación ni búsquedas nuevas en el servidor.

## Ampliación de la ficha de producto

El detalle incluye galería con miniaturas, marca, categoría, referencia de
publicación, atributos, descripción, existencias, variantes publicadas y nombre
y descripción de la tienda. Los datos provienen de `GET /api/products/{id}`.
La selección de variantes para comprar no forma parte de esta ampliación: se
muestran sus precios y existencias, y el carrito sigue agregando el producto base.

No se prometen descuentos, garantías, cuotas, envío gratis o fechas de entrega en
esta ficha si no están respaldados por datos. El costo de envío se consulta en el
checkout. La ficha no muestra los datos privados de la cuenta del vendedor.

Archivos adicionales:

| Archivo | Responsabilidad |
| --- | --- |
| `backend/demo/src/main/java/com/transformersas/marketplace/product/ProductDetailService.java` | Consulta imágenes, marca, atributos, variantes y perfil público de tienda, manteniendo las reglas de visibilidad. |
| `backend/demo/src/main/java/com/transformersas/marketplace/product/dto/ProductDetailResponse.java` | Respuesta ampliada del detalle, conservando los campos básicos anteriores. |
| `backend/demo/src/main/java/com/transformersas/marketplace/product/ProductController.java` | Conecta el endpoint de detalle con la consulta ampliada. |
| `backend/demo/src/test/java/com/transformersas/marketplace/product/ProductDetailIntegrationTests.java` | Prueba los datos publicados, los campos opcionales y las restricciones de acceso. |
| `scripts/fichas-productos-local.sql` | Carga marca, descripción y atributos ficticios para los diez productos demo de la tienda 1. |
| `scripts/desplegar-local.ps1` | Ejecuta la carga de fichas después de cargar el catálogo local. |

Las fichas demo se identifican con la marca `Marca demo local` y una descripción
de demostración. Son datos ficticios para probar la interfaz, no especificaciones
verificadas de artículos comerciales. La carga conserva precios, stock y estado;
no reemplaza marcas ni valores de atributos que ya se hayan configurado y solo
amplía descripciones que conservan el texto inicial o están vacías.

Para actualizar esta ampliación hacen falta backend y frontend nuevos, con Docker
Desktop abierto y las claves originales de la base disponibles:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desplegar-local.ps1 -Build
```

Después de actualizar, abrir una lámpara o cafetera demo y comprobar la tabla de
características y la descripción. Para verificar una galería con varias imágenes
deben existir imágenes publicadas por el vendedor.

Las nuevas pruebas del backend y la carga SQL real de las fichas quedan pendientes
de ejecución. La solicitud de ejecución de Maven no fue autorizada durante esta
ampliación; no se afirma que la suite de integración haya pasado.

## Archivos

| Archivo | Responsabilidad |
| --- | --- |
| `frontend/src/app/catalogo/catalogo-filtros.ts` | Búsqueda, normalización, filtros y ordenamiento sin modificar el listado original. |
| `frontend/src/app/catalogo/models/producto.model.ts` | Descripción y existencias del producto. |
| `frontend/src/app/catalogo/services/catalogo.service.ts` | Conversión común de listado y detalle desde la API. |
| `frontend/src/app/app.component.ts` | Estado de sesión, filtros, consulta de detalle y selección para comparación. |
| `frontend/src/app/app.component.html` | Controles, tarjetas, detalle y tabla de comparación. |
| `frontend/src/app/app.component.scss` | Presentación adaptable de filtros, detalle y comparación. |
| `frontend/e2e/catalogo.spec.ts` | Pruebas de navegador con API simulada. |
| `frontend/playwright.catalogo.config.ts` | Compilación y servidor aislado de prueba en el puerto 4401. |

## Demostración manual

1. Reconstruir el frontend con los cambios, manteniendo las credenciales de
   Compose disponibles en la terminal:

   ```powershell
   docker compose up -d --build --wait --no-deps frontend
   ```

   `--no-deps` evita reconstruir el backend; requiere que este ya esté funcionando.
2. Abrir `http://localhost:4300`, iniciar sesión como comprador y comprobar que
   el catálogo se carga sin recargar después del login. Si el navegador mantiene
   una versión anterior, cerrar todas las ventanas de incógnito y volver a abrir.
3. Buscar `CAFETERA` y después una palabra inexistente. Comprobar el listado y
   el mensaje sin coincidencias. Limpiar los filtros.
4. Filtrar por Hogar, ordenar por menor precio y establecer un precio mínimo.
   Comprobar que todos los resultados cumplen ambos filtros y el orden elegido.
5. Poner un mínimo superior al máximo y comprobar el mensaje de validación.
6. Abrir Ver detalle en la cafetera y verificar la descripción, precio y stock.
   Cerrar con el botón o Escape. Probar también el recorrido con Tab.
7. Comparar cafetera y lámpara de Hogar. Verificar la tabla y quitar una selección.
8. Intentar agregar una camiseta a la comparación de Hogar: debe mostrarse el
   mensaje de categoría incompatible. Vaciar y comparar dos productos de ropa.
9. Cerrar sesión: el catálogo solicita autenticarse y la comparación se limpia.

## Pruebas automatizadas de interfaz

Comprobaciones realizadas durante la implementación:

- Compilación Angular con `--optimization=false`: correcta.
- Comprobación TypeScript sin emitir archivos: correcta.
- Cuatro pruebas unitarias del catálogo y ocho pruebas existentes de pedidos:
  doce correctas, sin fallos.
- Playwright instalado y comprobación de tipos de las pruebas correcta.
- Primera ejecución en Chrome: una prueba correcta y tres fallidas por
  interrupciones en la descarga del JavaScript del servidor local. Se sustituyó
  ese servidor por `tests/servir-catalogo.cjs`, con compresión gzip. Falta repetir
  la suite completa después de ese ajuste; no se da por aprobada todavía.

Las pruebas unitarias del catálogo se ejecutan desde `frontend` con:

```powershell
node --test tests/catalogo-filtros.test.cjs
```

Con dependencias instaladas y Google Chrome disponible:

```powershell
cd frontend
npm.cmd run test:catalogo
```

Esta configuración usa API simulada y un servidor separado en 4401, sin tocar la
base de datos. No sustituye la prueba manual contra Spring Boot y MySQL. La
compilación de prueba desactiva optimización para evitar la descarga de fuentes
externas durante la validación; no cambia la configuración de Docker.

### Si VS Code no encuentra `@playwright/test`

Playwright abre un navegador y comprueba automáticamente los recorridos definidos
en los archivos `.spec.ts`. No es necesario ejecutarlo para usar la aplicación;
sirve para detectar regresiones y verificar cambios antes de integrarlos.

Un subrayado en `from '@playwright/test'` indica que la dependencia no se encuentra
en el entorno local. También puede producir errores derivados en parámetros como
`page` y `route`, porque el editor no conoce sus tipos. Desde `frontend`:

```powershell
npm.cmd ci
npm.cmd run test:catalogo
```

Si npm presenta un error de certificado y el equipo usa Node 24 compatible, se
puede usar el almacén de certificados del sistema en esa terminal, manteniendo TLS:

```powershell
$env:NODE_OPTIONS = '--use-system-ca'
npm.cmd ci
```

Si la instalación ya terminó pero el editor conserva los subrayados, ejecutar
**TypeScript: Restart TS Server** desde la paleta de comandos de VS Code. Esta
suite usa Google Chrome instalado en el equipo y el puerto 4401. No necesita
detener los contenedores de Compose ni descargar otro navegador.
El servidor de pruebas usa Node, sirve únicamente los archivos compilados y
termina al finalizar Playwright. Las trazas se guardan en
`artifacts/playwright-catalogo/` en la raíz del repositorio.
