# CI y publicación de imágenes en GHCR

El workflow `.github/workflows/backend-ci.yml` se ejecuta con push a cualquier rama, sin requerir Pull Requests.
No se activa al publicar tags. No realiza despliegues.

1. `validate` usa Java 21 y Docker del runner GitHub-hosted `ubuntu-24.04` para
   ejecutar `./mvnw clean verify` en `backend/demo`. Testcontainers administra su
   MySQL temporal; no se carga `.env` ni se levanta Compose.
2. Los reportes JaCoCo y Surefire disponibles se conservan durante 14 días,
   incluso si la prueba falla. Un fallo de Maven impide los jobs posteriores.
3. Fuera de `main`, `build` construye y carga las imágenes backend y frontend en el runner, sin publicarlas.
4. En `main`, `publish` construye y publica ambas imágenes en GHCR usando `GITHUB_TOKEN`.
   Solo este job recibe `packages: write`; los demás tienen `contents: read`.

Para este repositorio, las etiquetas son:

- `ghcr.io/transformersas/transformers-as-backend:latest`
- `ghcr.io/transformersas/transformers-as-backend:sha-<SHA completo del commit>`
- `ghcr.io/transformersas/transformers-as-frontend:latest`
- `ghcr.io/transformersas/transformers-as-frontend:sha-<SHA completo del commit>`

El nombre se deriva de `github.repository` en minúsculas. No se necesita
metadata-action para estas dos etiquetas. `latest` sigue la publicación de main;
la etiqueta SHA identifica el código usado, pero no impone inmutabilidad en GHCR.
Las imágenes base del Dockerfile siguen usando tags actualizables.

Las acciones se fijan por SHA completo, con su versión de release en comentarios.
Se verificaron los releases y commits mediante la API oficial de sus repositorios.
Al actualizarlas, verificar nuevamente ambos valores. Esto sigue la recomendación
de [GitHub sobre acciones inmutables](https://docs.github.com/en/actions/reference/security/secure-use).

La autenticación sigue la [guía oficial de publicación](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images):
GitHub proporciona un token temporal por job; no se requiere PAT ni secretos nuevos.
La organización debe permitir Actions y la creación/escritura del paquete. Si el
paquete ya existe, debe conceder acceso a este repositorio. Su visibilidad se
administra en GHCR y no se cambia desde este workflow.

## Validación local

Desde la raíz, con Java 21 y Docker activos:

```bash
docker info
(cd backend/demo && ./mvnw clean verify)
docker build -f backend/demo/Dockerfile -t transformers-as-backend:ci backend/demo
actionlint .github/workflows/backend-ci.yml
git diff --check
git diff --no-index -- /dev/null .github/workflows/backend-ci.yml
```

El último comando devuelve 1 al mostrar un archivo nuevo; es normal.
`actionlint` requiere instalación local. El lint y los builds locales no prueban
los permisos de GHCR: la primera ejecución en main verificará la publicación real.

En GitHub, abrir **Actions → Backend CI and GHCR**, seleccionar el commit y revisar
los jobs y sus logs. Descargar `backend-reports-<SHA>` desde Artifacts. En main,
comprobar también el paquete, ambas etiquetas y el digest en Packages. Conservar
estas salidas como evidencia. Un job omitido por la condición de rama es esperado.
