# Cambios para el arranque local y el despliegue distribuido

Este documento explica los archivos creados y modificados durante la preparación
del Marketplace. El objetivo de la actividad es iniciar frontend, backend y base
de datos mediante un único script y comprobar su funcionamiento en al menos dos
computadores.

## Archivos y propósito

| Archivo | Cambio | Para qué sirve |
| --- | --- | --- |
| [`scripts/desplegar-local.ps1`](../../scripts/desplegar-local.ps1) | Creado | Automatiza el arranque con Docker Compose en Windows y la carga del catálogo de ejemplo. |
| [`scripts/catalogo-local.sql`](../../scripts/catalogo-local.sql) | Creado | Inserta diez productos de ejemplo en la tienda principal. |
| [`scripts/ajustar-catalogo-local.sql`](../../scripts/ajustar-catalogo-local.sql) | Creado | Ajuste puntual local: conserva una lámpara LED activa, retira el reloj demo y agrega una cafetera sin borrar el historial. |
| [`LocalDemoAccountConfiguration.java`](../../backend/demo/src/main/java/com/transformersas/marketplace/users/infrastructure/config/LocalDemoAccountConfiguration.java) | Modificado | Marca como verificado el correo de la cuenta demo al arrancar con el perfil `local`. |
| [`LocalDemoAccountConfigurationTests.java`](../../backend/demo/src/test/java/com/transformersas/marketplace/users/infrastructure/config/LocalDemoAccountConfigurationTests.java) | Modificado | Agrega comprobaciones de verificación del correo para la cuenta nueva y la existente, incluyendo la repetición del arranque. |
| [`deploy.sh`](../../deploy.sh) | Modificado | Agrega captura automática de evidencia al terminar correctamente el despliegue y corrige la ruta de ayuda. |
| [`docker-swarm.md`](docker-swarm.md) | Modificado | Documenta la ejecución en dos computadores, corrige la explicación del reparto de réplicas e indica qué evidencia adjuntar. |
| [`README.md`](../../README.md) | Modificado | Añade las instrucciones para ejecutar el arranque local de Windows con productos de ejemplo. |

## Arranque local: PowerShell y catálogo

`desplegar-local.ps1` comprueba Docker, utiliza las contraseñas del entorno o de
`.env` y activa el perfil `local`. Si no existe `.env` y faltan contraseñas en el
entorno, las solicita de forma oculta. Si existe `.env`, debe contener las claves
necesarias. Con una base existente se deben conservar sus contraseñas originales.

Después valida Compose, inicia frontend, backend y MySQL, espera la comprobación
de readiness y ejecuta `catalogo-local.sql` dentro del contenedor MySQL. Finalmente
muestra los servicios y los datos de acceso. No guarda las contraseñas en archivos.

Desde la raíz del proyecto, con Docker Desktop abierto:

```powershell
# Primera ejecución o después de cambiar código.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desplegar-local.ps1 -Build

# Arranques posteriores sin cambios de código.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desplegar-local.ps1
```

La opción `-Build` reconstruye las imágenes. Sin ella se reutilizan las existentes.
`-ExecutionPolicy Bypass` se aplica al proceso de PowerShell que ejecuta el archivo.

El catálogo incluye ropa, calzado, accesorios, tecnología y hogar, con precios y
existencias iniciales. No incluye imágenes. Al repetir la carga se omiten los
productos con el mismo nombre y tienda, sin restablecer precios, stock ni estados.
No ejecutar dos cargas simultáneas.

El catálogo actualizado incluye una cafetera de goteo en lugar del reloj digital.
Para una base local que ya tuviera el reloj y las lámparas duplicadas se agregó
`scripts/ajustar-catalogo-local.sql`, aplicado durante este ajuste. Es un cambio
puntual separado del arranque: retira las publicaciones anteriores conservando
sus IDs y referencias. Repetirlo conserva la lámpara activa y no duplica la cafetera.

El frontend se abre en `http://localhost:4300`, salvo un puerto personalizado.
La cuenta local es `demo@marketplace.local`, con contraseña `MarketplaceDemo123!`.
La corrección en Java permite verificar esa cuenta sin depender del envío de correo.
Solo se aplica con el perfil `local`; no desactiva la verificación general de usuarios.

Este procedimiento ejecuta todo en un computador y sirve para desarrollo local.
La prueba del requisito de distribución se realiza con Swarm.

## Despliegue distribuido: Bash y Docker Swarm

`deploy.sh` ya incluía la validación del manager, la exigencia de al menos dos
nodos Linux Ready/Active en modo real, la preparación de secrets, el despliegue de
los tres servicios y la espera de réplicas y respuestas de salud. También comprueba
NodeID distintos para las réplicas de frontend y backend.

El ajuste realizado agrega archivos de evidencia después de la convergencia:

- Resumen de ejecución y revisión del checkout.
- Nodos, servicios y tareas del stack.
- Referencias de las imágenes desplegadas.
- NodeID y estado de las tareas de cada servicio.
- Respuestas de salud del frontend y readiness de la API.

Se guardan en `artifacts/deploy-<fecha UTC>-<proceso>/`, ignorado por Git. La variable
`DEPLOY_EVIDENCE_DIR` permite elegir otra carpeta; debe usarse una nueva por ejecución.
No se exportan contenidos de secrets ni variables de los contenedores. La revisión
del checkout identifica el script utilizado; las referencias de imagen identifican
los artefactos desplegados.

El comando de despliegue es `bash deploy.sh`, desde el manager, después de preparar
el cluster, el acceso a GHCR, las etiquetas de imagen y las claves. La guía
[`docker-swarm.md`](docker-swarm.md) detalla esos pasos. El modo `--local` de este
script es un ensayo Swarm de un nodo y no acredita dos computadores.

El catálogo de Compose no se carga automáticamente en Swarm. El stack distribuido
tampoco activa la cuenta del perfil `local`.

## Configuración relacionada que se revisó

[`stack.yml`](../../stack.yml) se revisó, pero no se modificó en este trabajo.
Define dos réplicas de frontend, dos de backend y una de MySQL. En modo real limita
frontend y backend a una réplica de cada servicio por nodo. MySQL queda fijado al
manager seleccionado para conservar su volumen local.

La guía se corrigió para reflejar esa restricción: con un solo nodo disponible,
la segunda réplica de cada servicio de aplicación puede quedar pendiente. MySQL
no tiene alta disponibilidad por tener una única réplica.

## Estado de las comprobaciones

| Comprobación | Estado registrado durante el trabajo |
| --- | --- |
| Sintaxis de `deploy.sh` | Validada con `bash -n`. |
| Interpretación de `stack.yml` | Validada con `docker stack config` usando nombres e ID ficticios. |
| Script PowerShell | Sintaxis y flujo simulado comprobados; falta confirmar el recorrido completo con Docker y la carga SQL real. |
| Prueba Java de preparación de la cuenta | No se pudo ejecutar: el Maven Wrapper local falló al iniciar. |
| Prueba automatizada `test_deploy_guard.py` | Falló al ejecutarse en Windows/Git Bash; falta diagnosticarla y repetirla en el entorno adecuado. |
| Despliegue en dos computadores | Pendiente. El equipo consultado tenía Swarm inactivo. |
| Evidencia de ejecución distribuida | Pendiente de generar en los dos equipos. |

Estos resultados no equivalen a una certificación completa del requisito.

## Qué falta para cerrar la actividad

1. Resolver las comprobaciones automatizadas pendientes.
2. Preparar el computador propio y el de un compañero como dos nodos Swarm,
   comprobando conectividad entre sus motores Docker Linux. Ambos equipos son
   Windows; debe comprobarse la red de Docker Desktop y, si hace falta, usar una
   máquina virtual Linux por computador con conectividad entre ellas.
3. Ejecutar `bash deploy.sh` una vez desde el manager con la configuración real.
4. Comprobar frontend y backend 2/2, MySQL 1/1 y tareas distribuidas en NodeID
   correspondientes a computadores distintos.
5. Acceder al sistema desde el segundo computador y registrar una operación
   funcional con cuentas verificadas y datos de prueba preparados.
6. Repetir el despliegue y comprobar la conservación de los datos.
7. Adjuntar la evidencia generada y capturas, sin contraseñas ni tokens.

Los archivos `__pycache__/` y `*.pyc` son temporales de Python y no forman parte
del entregable.
