# Imágenes ilustrativas disponibles sin proveedor externo

La portada y las imágenes ilustrativas de productos sin fotografías propias dependían de `images.unsplash.com`. Cuando ese servicio tardaba o devolvía errores, se mostraban espacios vacíos y el texto alternativo aunque el catálogo estuviera disponible.

Se conservan las mismas fotografías ilustrativas, ahora en `frontend/src/assets/catalogo-demo/`. Angular las incluye en la compilación y Nginx las entrega desde el mismo origen que la aplicación. No requieren acceder a Unsplash al abrir la demo por localhost o túnel HTTPS. Las fuentes originales y la fecha de descarga están en `origenes.json`, junto a los archivos.

`CatalogoService` sigue priorizando las fotografías `imageUrls` aportadas por cada publicación. Las copias locales se utilizan únicamente como ilustraciones cuando no hay fotografías propias; no representan evidencia del estado del producto real. Una fotografía externa suministrada por un vendedor sigue dependiendo de su origen y puede fallar: este cambio no incorpora un servicio general de almacenamiento ni reemplaza sus fotografías.

La imagen genérica para nombres/categorías desconocidos conserva su comportamiento. No se modifica CSP, la autenticación, los pagos ni la base de datos para resolver este problema.

Después de reconstruir el contenedor del frontend se verificaron por HTTP las 25 fotografías locales: todas respondieron 200, con tipo `image/jpeg` y contenido idéntico al archivo del proyecto (comparación SHA-256). Registro local generado: `artifacts/imagenes-http-verificacion.json`. La comprobación adicional del navegador sobre el túnel no llegó a ejecutarse por permisos; no se presenta como evidencia obtenida.

Validación local del 14 de noviembre de 2026: compilación Angular completada y **6 pruebas Playwright aprobadas** (catálogo e inicio). Una prueba nueva bloquea Unsplash y comprueba que la portada y dos productos sin fotos propias cargan realmente (`naturalWidth > 0`); también verifica que se conserva la URL de una fotografía aportada por una publicación. No equivale a probar todas las imágenes que puedan subir futuros vendedores.

Para aplicar el cambio en Docker, reconstruir frontend conservando los archivos Compose del entorno; por ejemplo, con push y túnel:

```powershell
docker compose --env-file .env --env-file .env.push -p marketplace-pruebas -f compose.yaml -f compose.notificaciones.yaml -f compose.tunel.yaml up -d --build --no-deps frontend
```

Después, recargar la página. No es necesario cambiar el dominio ni reiniciar la base de datos o el backend.
