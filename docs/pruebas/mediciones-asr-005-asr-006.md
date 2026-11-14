# Mediciones de ASR-005 (rendimiento) y ASR-006 (despliegue)

Resultados medidos el **11 de noviembre de 2026** sobre el código de `main` en `c8f4d453`, más los arreglos que salieron de estas mismas mediciones (sección 3).

Después de esa versión entraron a `main` las reseñas (CU-05), las notificaciones push y cambios de CU-08 (migraciones V42 a V44). El listado del catálogo ahora incluye las calificaciones de cada producto. Los arreglos de la sección 3 están aplicados sobre ese `main` más nuevo, pero **los tiempos no se han vuelto a medir con él**.

**Todo se midió en un solo computador.** La última columna de cada tabla dice si la prueba debe repetirse con los dos computadores del clúster; el resumen está en la sección 6.

## 1. Entorno de la medición

| Elemento | Valor |
|---|---|
| Computador | 1 PC con Windows 11; Docker Engine dentro de Ubuntu (WSL 2), 12 hilos de CPU y 7 GB de memoria para WSL |
| Clúster | Docker Swarm de un nodo, desplegado con `deploy.sh --local` en un stack aparte (`asr`) con base de datos nueva |
| Servicios | 2 réplicas de frontend (Nginx), 2 réplicas de backend, 1 MySQL 8.4.11 |
| Datos | `scripts/performance-demo-seed.sh`: 1.000 productos, 100 cuentas de comprador y 10.000 pedidos, además de los datos de demostración |
| Carga | k6 v0.57.0, 1 minuto por corrida, contra el frontend (Nginx reparte entre las dos réplicas) |
| Usuarios | Cada usuario virtual entra con su propia cuenta (`buyer001` a `buyer100`) y no espera entre peticiones, salvo en la compra (1 s entre compras) |
| Pago | Pasarela simulada (`PAYMENTS_MOCK_ENABLED=true`): mide el procesamiento interno, sin el tiempo de Mercado Pago |

Límites de este entorno que hay que decir al presentar los números:

- k6 corrió en el mismo computador que las réplicas y MySQL, así que compitió con ellos por CPU.
- Entre réplicas, y entre réplicas y MySQL, no hubo red real.
- El login de cada usuario virtual (BCrypt) ocurre al empezar y no entra en las métricas, pero sí carga la CPU en los primeros segundos: de ahí salen los máximos de unos 3 s.

## 2. ASR-005 — Rendimiento con 100 usuarios concurrentes

Criterios del SAD: catálogo y búsqueda P95 ≤ 3 s; carrito, favoritos, perfil y direcciones P95 ≤ 2 s; procesamiento interno del checkout ≤ 3 s; flujos principales P95 ≤ 4 s; errores < 2 %.

### 2.1 Resultado final (con los arreglos de la sección 3)

| Criterio | Límite | Mediana | P95 | Errores | Resultado | ¿Repetir con dos PCs? |
|---|---|---|---|---|---|---|
| Catálogo: listado, 50 usuarios | 3 s | 155 ms | **391 ms** | 0 % | Cumple | Sí |
| Catálogo: detalle, 50 usuarios | 3 s | 120 ms | **326 ms** | 0 % | Cumple | Sí |
| Catálogo: listado, 100 usuarios | 3 s | 215 ms | **501 ms** | 0 % | Cumple | Sí |
| Catálogo: detalle, 100 usuarios | 3 s | 169 ms | **408 ms** | 0 % | Cumple | Sí |
| Carrito (agregar, ver, cambiar cantidad, quitar) | 2 s | 146 ms | **293 ms** | 0 % | Cumple | Sí |
| Favoritos (guardar, listar, quitar) | 2 s | 132 ms | **261 ms** | 0 % | Cumple | Sí |
| Perfil | 2 s | 95 ms | **204 ms** | 0 % | Cumple | Sí |
| Direcciones (crear, listar) | 2 s | 109 ms | **245 ms** | 0 % | Cumple | Sí |
| Checkout interno (reservar + calcular total + pagar) | 3 s | 263 ms | **454 ms** | 0 % | Cumple | Sí |
| Flujo de compra completo (agregar al carrito + checkout) | 4 s | 361 ms | **595 ms** | 0 % | Cumple | Sí |

La **búsqueda** se hace en el navegador sobre el listado ya descargado; no hay una petición de búsqueda al servidor. Su tiempo de servidor es el del listado.

Volumen atendido en cada corrida de 100 usuarios:

| Prueba | Peticiones | Por segundo | Iteraciones completas |
|---|---|---|---|
| Catálogo | 26.948 | 411 | 13.372 recorridos listado + detalle |
| Carrito, favoritos, perfil y direcciones | 41.214 | 625 | 4.081 |
| Compra | 17.676 | 276 | 4.293 pedidos creados (67 por segundo) |

### 2.2 Antes y después (100 usuarios, P95)

| Criterio | Límite | Antes | Solo con el pool ampliado | Final |
|---|---|---|---|---|
| Catálogo: listado | 3 s | 1.325 ms | — | **501 ms** |
| Catálogo: detalle | 3 s | 1.044 ms | — | **408 ms** |
| Carrito | 2 s | 620 ms | — | **293 ms** |
| Favoritos | 2 s | 598 ms | — | **261 ms** |
| Perfil | 2 s | 492 ms | — | **204 ms** |
| Direcciones | 2 s | 612 ms | — | **245 ms** |
| Checkout: reservar inventario | — | 934 ms | 372 ms | **180 ms** |
| Checkout: calcular total | — | 1.490 ms | 299 ms | **118 ms** |
| Checkout: pagar y crear el pedido | — | 993 ms | 541 ms | **233 ms** |
| Checkout interno (suma de los tres) | 3 s | **3.537 ms — no cumplía** | 1.184 ms | **454 ms** |
| Flujo de compra completo | 4 s | **4.158 ms — no cumplía** | 1.544 ms | **595 ms** |

Antes de los arreglos no hubo errores en ninguna corrida: lo que no cumplía era el tiempo de la compra.

## 3. Qué se corrigió

### 3.1 Pool de conexiones a MySQL: de 10 a 30 por réplica

Cada réplica del backend abría como máximo 10 conexiones (el valor por defecto de HikariCP). Con 100 compradores, las peticiones hacían cola esperando una conexión. Además, las notificaciones que se envían después de confirmar un pedido usan conexiones del mismo pool.

- Cambio: `spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:30}` en `application.properties`.
- Efecto medido con solo este cambio: checkout interno de 3.537 ms a 1.184 ms.
- Límite: réplicas × pool debe quedar por debajo de `max_connections` de MySQL (151 por defecto). Con 2 réplicas son 60 conexiones; con 4 serían 120.

### 3.2 Catálogo: el filtro pasa de Java a MySQL

`GET /api/products` cargaba todos los productos con `findAll()`, con la entidad completa, y descartaba en Java los borradores, los retirados, los ocultos por moderación y los de tiendas suspendidas.

- Cambio: `ProductRepository.findCatalog` filtra en la consulta y trae solo las siete columnas del listado.
- La respuesta es la misma: el frontend no cambia.
- Pruebas: 132 pruebas de integración de las 6 clases que más usan el catálogo, sin fallos.

### 3.3 Índices: no hizo falta crear ninguno

Después de las corridas de carga se consultó en MySQL (`performance_schema`) qué consultas no usaban índice y cuántas filas examinaba cada una, con 1.013 productos y más de 15.000 pedidos:

| Consulta | Veces | Filas examinadas por ejecución | ¿Usa índice? |
|---|---|---|---|
| Sesión (`SPRING_SESSION`) | 87.851 | 2 | Sí |
| Cuenta y roles del usuario | 69.000 cada una | 1 | Sí |
| Producto por id | 42.661 | 1 | Sí |
| Carrito por cuenta | 33.976 | 1 | Sí |
| Líneas del carrito | 25.706 | 1 | Sí |
| Reservas de inventario | 11.504 | 0 a 1 | Sí |
| Listado completo del catálogo | 7.108 | 1.013 | No: devuelve toda la tabla, 2,5 ms en promedio |
| Tiendas que no pueden vender | 7.106 | 7 | No: la tabla tiene 7 filas |

Las dos consultas sin índice recorren la tabla porque devuelven casi todas sus filas; un índice no las aceleraría.

### 3.4 Caché de negocio: se decidió no usarla

Spring Cache y Caffeine siguen configurados pero sin cachés de negocio. La candidata era el listado del catálogo. Razones para no activarla:

- La consulta cuesta 2,5 ms en MySQL y el criterio se cumple con un margen de seis veces (501 ms frente a 3 s).
- Caffeine guarda una copia por réplica. Dos réplicas mostrarían stock distinto, y un producto ocultado por moderación o una tienda suspendida seguirían visibles en la otra réplica hasta que venciera la copia.

Si el catálogo crece hasta que el listado completo deje de cumplir, el siguiente paso es paginar y buscar en el servidor, no la caché.

## 4. ASR-006 — Despliegue reproducible y recuperable

Criterios del SAD: despliegue operativo mediante una entrada en ≤ 15 minutos; credenciales fuera del código; retorno a una versión estable en ≤ 15 minutos sin pérdida de datos confirmados.

### 4.1 Tiempos

| Criterio | Límite | Medido | Resultado | ¿Repetir con dos PCs? |
|---|---|---|---|---|
| Instalación limpia con un comando (`bash deploy.sh --local`) | 15 min | **≈ 11 min 03 s** | Cumple | Sí: el modo real es otro camino del script |
| — compilar las dos imágenes sin caché | | 9 min 07 s | | |
| — crear secretos, desplegar y esperar a que todo esté sano | | 1 min 56 s | | |
| Actualización gradual a una versión nueva (con compilación en caché) | — | 3 min 57 s | | Sí |
| Rollback del backend a la versión anterior | 15 min | **2 min 38 s** | Cumple | Sí |

Sobre la instalación limpia:

- "Limpia" significa stack nuevo, volumen de MySQL nuevo, secretos nuevos y caché de compilación de Docker borrada. Las imágenes base (JDK, Node, Nginx, MySQL) ya estaban descargadas.
- El 1 min 56 s incluye que MySQL arranque, que Flyway aplique las 41 migraciones y que se carguen los datos de demostración.
- El tiempo es la suma de dos corridas. La primera compiló las imágenes y falló al crear el secreto por un error en el comando de la prueba, no del script; la segunda desplegó con las imágenes ya compiladas.
- En el modo real (`bash deploy.sh`, dos computadores) las imágenes no se compilan: se descargan de GHCR. Ese tiempo no se ha medido.

### 4.2 Rollback, migraciones y datos

Se volvió del backend actual (migraciones hasta V41) al de la versión anterior a CU-26 y CU-27 (migraciones hasta V39), sobre la misma base de datos ya migrada a V41, con `docker service update --image`. Solo se revirtió el backend, no el frontend.

| Comprobación | Antes del rollback | Después del rollback |
|---|---|---|
| Pedidos | 15.762 | 15.762 |
| Líneas de pedido | 15.762 | 15.762 |
| Cuentas | 112 | 112 |
| Productos | 1.013 | 1.013 |
| Suma de los totales de los pedidos | 2.067.445.166 | 2.067.445.166 |
| Versión del esquema | V41 | V41 |

- **Arranque de la versión anterior sobre el esquema nuevo:** las dos réplicas arrancaron con 0 errores. Flyway solo avisó de que el esquema (41) es más nuevo que su última migración (39) y no cambió nada.
- **Uso después del rollback:** la versión anterior leyó los datos existentes y creó 78 pedidos nuevos en una prueba corta de compra, con 0 % de errores.
- **Disponibilidad durante el rollback:** de 274 comprobaciones de salud a través de Nginx (una cada medio segundo) falló 1. Las réplicas se reemplazan de una en una.

Lo que este rollback no cubre: revierte la imagen, no el esquema ni los datos. Funcionó porque V40 y V41 solo agregan una columna opcional y tablas nuevas. Una migración que borre o renombre algo que la versión anterior usa no sería reversible de esta forma.

### 4.3 Credenciales fuera del código

| Revisión | Resultado |
|---|---|
| Archivos sensibles versionados (`.env`, llaves, certificados) | Solo `.env.example`, con marcadores; `.env` está en `.gitignore` y nunca se subió |
| Contraseñas o tokens en el código versionado | Ninguno de producción |
| Variables de entorno de las dos imágenes | Sin contraseñas ni tokens |
| Historial de construcción de las dos imágenes | Sin contraseñas ni tokens |
| Archivos `.env` o llaves dentro de las imágenes | Ninguno |
| Contenedor del backend en ejecución | La contraseña de MySQL no está en su entorno; llega como archivo de Docker Secrets, legible solo por el usuario de la aplicación |

**Credenciales de demostración.** La contraseña de las cuentas demo sí está en el código (`datos-demo.sql`, `DemoProvisioning`, `LocalDemoAccountConfiguration`). Esas cuentas solo se crean con `MARKETPLACE_DEMO_DATA=true` o con los perfiles `demo` y `local`. En `application.properties` viene apagado; `stack.yml` y `compose.yaml` lo encienden por defecto porque son los entornos de demostración. En un despliegue que no sea de demostración hay que definir `MARKETPLACE_DEMO_DATA=false`.

Una contraseña de prueba aparece también en `scripts/k6/seller-register.js`: es de cuentas que crea esa misma prueba de carga.

## 5. Cómo repetir las mediciones

Requisitos: Docker con Swarm, k6, Python 3 y una base **desechable**. La prueba de compra crea miles de pedidos reales y el generador de datos no es para una base con información que importe.

```bash
# 1. Stack aparte, con sus propios puertos y secretos (archivos de una línea, sin salto final)
STACK_NAME=asr BACKEND_HOST_PORT=28080 FRONTEND_HOST_PORT=28000 \
DB_PASSWORD_SECRET_FILE=<archivo> MYSQL_ROOT_PASSWORD_SECRET_FILE=<archivo> \
bash deploy.sh --local

# 2. Pago simulado, solo en este stack de pruebas
docker service update --env-add PAYMENTS_MOCK_ENABLED=true asr_backend

# 3. Datos representativos (ver datos-demostracion-y-rendimiento.md)
MYSQL_CONTAINER=<contenedor asr_mysql> DB_NAME=marketplace ./scripts/performance-demo-seed.sh

# 4. Carga: una corrida por script
export BASE_URL=http://127.0.0.1:28000 VUS=100 DURATION=1m
export CATALOG_EMAIL=buyer001@marketplace.demo CATALOG_PASSWORD=<DEMO_PASSWORD>
export ACCOUNT_PATTERN='buyer{n}@marketplace.demo' ACCOUNT_PASSWORD=<DEMO_PASSWORD>
k6 run --summary-export catalogo.json scripts/k6/catalog-browse.js
k6 run --summary-export recursos.json scripts/k6/buyer-resources.js
k6 run --summary-export compra.json   scripts/k6/checkout-flow.js

# 5. Rollback del backend a una imagen anterior, o a la configuración previa del servicio
docker service update --image ghcr.io/transformersas/transformers-as-backend:<tag anterior> asr_backend
docker service rollback asr_backend
```

Cada script falla (código de salida distinto de 0) si no se cumple su límite. Los archivos originales de estas corridas (resúmenes JSON y logs de k6, tiempos de despliegue y conteos del rollback) quedaron en `artifacts/asr-005-006-2026-11-11/`, carpeta que Git ignora: solo existen en el computador donde se midió.

## 6. Qué falta medir con dos computadores

| Prueba | Por qué cambia el resultado | Cómo |
|---|---|---|
| Las tres pruebas de carga con 100 usuarios | El tráfico entre Nginx, las réplicas y MySQL cruza la red entre los dos equipos, y k6 deja de competir por la misma CPU | Los tres scripts de la sección 5, con `BASE_URL` apuntando a la IP del manager |
| Reparto entre réplicas | Es lo que demuestra que cada computador atiende su parte | `python3 scripts/quality/run.py balancing --base-url http://IP_MANAGER --stack transformers` |
| Caída de una réplica bajo carga | En un solo PC la réplica se recrea en el mismo equipo | `run.py availability` y matar el contenedor del backend en el worker |
| Apagar un computador | No se puede probar con un solo equipo | Apagar el **worker** con la carga corriendo |
| Instalación con un comando en modo real | `bash deploy.sh` descarga las imágenes de GHCR y exige una réplica por equipo; aquí se midió `--local`, que las compila | `bash deploy.sh` en el manager, con cronómetro |
| Rollback | Las réplicas se reemplazan en equipos distintos | `BACKEND_IMAGE_TAG=sha-<anterior> bash deploy.sh` o `docker service rollback` |

No hace falta repetir con dos computadores: la revisión de secretos (sección 4.3), la de índices (sección 3.3) y la compatibilidad de la versión anterior con el esquema nuevo (sección 4.2). No dependen del número de equipos.

Limitaciones que seguirán existiendo con dos computadores:

- MySQL tiene una sola instancia y vive en el manager. Si se apaga ese equipo, el sistema deja de atender; por eso la prueba de apagado se hace con el worker.
- La pasarela de pago seguirá siendo la simulada en las pruebas de carga.
