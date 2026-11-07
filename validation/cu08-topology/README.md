# Validación de cuatro redes: preparación, sin ejecución

El laboratorio normal ya se ejecutó: infraestructura y HTTP aprobaron, pero el
login visual falló por CORS. El laboratorio demo no se ha iniciado. La corrección
CORS aquí preparada todavía no se aplicó a los contenedores y requiere una etapa
posterior autorizada. No usar Marketplace.exe ni archivos .env/.env.demo.
No usar la base, redes, volumen o contenedores de validation/cu08-h02.

## Configuración realmente reutilizada

Normal: compose.yaml de la raíz + limits.override.yaml + normal-cors.override.yaml
de esta carpeta.
Demo: compose.yaml + compose.demo.yaml de la raíz + el mismo override final.
El directorio de proyecto es la raíz, para conservar contextos de build y el
montaje de frontend/nginx.compose.conf. No se copia la plantilla Nginx.

El override únicamente asigna imágenes exclusivas, límites, readiness del backend
y H02 explícitamente apagado. No replica ni sustituye redes, alias, volúmenes o
puertos. Los puertos, base y credenciales de prueba se resuelven por las variables
ya admitidas en los manifiestos reales. Normal fuerza SPRING_PROFILES_ACTIVE=local
igual que desplegar-local.ps1, además del perfil incluido compose-local. Demo
conserva demo, sus adaptadores simulados y la fixture repetible; NO representa el
procedimiento oficial de entrega por el mero hecho de estar disponible.
El seeder de datos de demostración habilitado por los manifiestos raíz trabajaría
solo en las bases nuevas. El seed del laboratorio normal ya fue validado en la
etapa anterior; esta corrección CORS no vuelve a ejecutarlo.

### CORS exclusivo del laboratorio normal: corrección pendiente de aplicar

La primera validación real confirmó las cuatro redes, SQL y autenticación HTTP,
pero el login de Chrome respondió 403 Invalid CORS request. La propiedad de la
aplicación `app.cors.allowed-origins`, consumida por
SecurityConfiguration.corsConfigurationSource(), no incluye el origen alternativo
http://127.0.0.1:14430. El diagnóstico saneado está en
evidencias/normal/cors-diagnostic.json: incluso GET /api/auth/csrf devuelve ese
rechazo al enviar dicho Origin; sin Origin o con http://localhost:4300 responde
200. Esto distingue el rechazo CORS de un fallo de credenciales o token CSRF.

normal-cors.override.yaml añade APP_CORS_ALLOWED_ORIGINS solo al backend normal:
conserva http://localhost:4300, http://localhost:4200 y http://localhost:8100 y
agrega exactamente http://127.0.0.1:14430. No autoriza localhost:14430, otras IP,
otros puertos, comodines ni rangos. El wrapper comprueba la lista exacta y no
carga este override para demo. No se toca la configuración raíz/producción.

La corrección no modifica allowCredentials, métodos/cabeceras permitidos, CSRF,
cookies, sesiones ni H02: únicamente amplía la lista explícita del laboratorio.
No se ha recreado el backend para aplicarla. El script start rechaza recursos
existentes; no usarlo ni limpiar la base para aplicar este cambio. En una etapa
posterior autorizada habrá que recrear SOLO backend con los mismos manifiestos,
proyecto, entorno de prueba y el override nuevo, conservando MySQL/frontend.
Comprobar entonces la propiedad efectiva, rechazo de un origen no autorizado y
login, restauración de sesión, CSRF inválido y logout en Chrome. No ejecutar esos
pasos todavía ni atribuirles éxito a partir de la validación estática.

## Recursos exclusivos

| Recurso | Normal | Demo |
| --- | --- | --- |
| Proyecto | cu08-topology-normal-20261107 | cu08-topology-demo-20261107 |
| Frontend, loopback | 14430 | 14431 |
| Backend, loopback | 18430 | 18431 |
| MySQL, loopback | 13430 | 13431 |
| Base | cu08_topology_normal | cu08_topology_demo |
| Volumen | cu08-topology-normal-20261107_mysql_data | cu08-topology-demo-20261107_mysql_data |
| IP Nginx confiable | 172.30.238.10 | 172.30.239.10 |
| Subred proxy | 172.30.238.0/24 | 172.30.239.0/24 |

Cada imagen de aplicación se llama PROYECTO-frontend:validation o
PROYECTO-backend:validation. MySQL usa la imagen oficial compartida, de solo
lectura; sus contenedores, credenciales, volumen y datos son independientes.

Cada proyecto recibe sus propias cuatro redes, sin external ni nombres globales:

| Red | Interna | Miembros |
| --- | --- | --- |
| web_entry | no | frontend y backend |
| login_proxy | sí | frontend y backend |
| database | sí | backend y MySQL |
| mysql_host | no | MySQL únicamente |

backend-login-proxy es alias del backend exclusivamente en login_proxy. Nginx
recibe ese alias como upstream; no usa el nombre multirred backend. database,
mysql_host y web_entry tienen subred asignada por Docker. Revisión LAN/VPN sigue
siendo necesaria: la selección automática no conoce todas las rutas del host.

Límites de runtime: frontend 0.25 CPU/128 MiB, backend 1 CPU/1536 MiB,
MySQL 0.75 CPU/768 MiB. Ejecutar un laboratorio cada vez reduce carga. Los builds
secuenciales no tienen esos límites y usan la caché BuildKit del Dockerfile raíz.
MySQL puede acceder hacia afuera mediante mysql_host; no existe aislamiento de
egress completo. La segmentación no limita al administrador del daemon Docker.

## Procedimientos separados

Desde la raíz, usar únicamente control.py. El primer argumento obligatorio es
normal o demo y el segundo es una acción permitida. No admite un proyecto libre,
argumentos Docker extra ni acciones por defecto. Argumentos inválidos fallan
antes de consultar Docker. No hay fallback a marketplace-demo ni limpieza al salir.

El wrapper construye un entorno de proceso nuevo, conserva solo PATH/HOME y
variables de prueba definidas en el script, fija desktop-linux (socket local),
--env-file /dev/null, proyecto y archivos absolutos. No lee dotenv ni utiliza
credenciales de aplicación heredadas. Las fixtures son públicas, distintas por
laboratorio y no deben utilizarse en despliegues reales. No imprimir config sin
filtrar, ni inspeccionar Config.Env, para evitar publicar credenciales incluso
si son de prueba.

### Revisar (solo lectura)

```bash
python3 validation/cu08-topology/control.py normal config
python3 validation/cu08-topology/control.py demo config
python3 validation/cu08-topology/control.py normal preflight
python3 validation/cu08-topology/control.py demo preflight
```

config audita el modelo efectivo de Compose sin imprimirlo. preflight comprueba
además ausencia de contenedores/redes/volumen propios, puertos libres y conflictos
del proxy con redes Docker. Inspeccionar también netstat/route y VPN antes de
autorizar creación. La disponibilidad puede cambiar después de la inspección.

### Construir (NO ejecutado)

```bash
python3 validation/cu08-topology/control.py normal build
python3 validation/cu08-topology/control.py demo build
```

Rechaza etiquetas ya existentes, para no sobrescribir imágenes. Si existen,
detenerse y revisar; no borrarlas automáticamente. Usa los Dockerfiles raíz.

### Iniciar (NO ejecutado)

```bash
python3 validation/cu08-topology/control.py normal start
python3 validation/cu08-topology/control.py demo start
```

start requiere imágenes ya construidas y recursos nuevos; no construye ni reutiliza
volúmenes existentes. Crearía redes y volumen propios y ejecutaría Flyway en esa
base nueva al arrancar. No repetir start para actualizar un laboratorio existente:
el wrapper lo rechaza. Nunca iniciar estas pruebas usando Marketplace.exe.

### Inspeccionar (solo metadatos, sin ejecución de pruebas)

```bash
python3 validation/cu08-topology/control.py normal inspect
python3 validation/cu08-topology/control.py demo inspect
```

Imprime IDs, salud, puertos e interfaces, nunca variables de entorno ni respuestas
HTTP. Verifica pertenencia de redes/volumen/montajes antes de operar.

### Preparar catálogo local, SOLO normal (NO ejecutado)

Después de construir e iniciar el laboratorio normal con autorización:

```bash
python3 validation/cu08-topology/control.py normal seed-local
```

Es una acción explícita, nunca automática durante config, preflight o start.
`demo seed-local` se rechaza antes de acceder a Docker. No acepta proyecto,
servidor, usuario, ruta SQL ni base como argumentos adicionales. Python con -O
también se rechaza: las comprobaciones de aislamiento deben permanecer activas.

El perfil local ejecuta LocalDemoAccountConfiguration: crea la cuenta de desarrollo
si falta, con los roles comprador/vendedor; si existe, conserva su contraseña y
roles. Marca el correo como verificado incluso si ya existía y asigna la tienda
principal solo conforme a las reglas de AssignStoreOwnerUseCase. No reactiva por
sí mismo una cuenta que ya estuviera inactiva. LocalDemoProductConfiguration
inserta su producto si falta, sin restablecer stock o estado de uno existente.
Además permanece el seeder de datos de demostración del Compose raíz. Estas
escrituras ocurrirían al arrancar SOLO contra la base nueva del laboratorio.
Los SQL de catálogo NO crean ni verifican cuentas. Comprobar después mediante
consulta booleana que la cuenta fixture está activa/verificada; no publicar sus
credenciales, hashes ni datos personales.

seed-local exige recursos del proyecto normal, backend/MySQL saludables y
variables runtime de base/perfil coincidentes. Verifica el volumen exclusivo,
espera readiness con las mismas opciones de curl del PowerShell y comprueba
SELECT DATABASE(), @@hostname contra la base y el contenedor inspeccionados.
Usa el ID exacto de ese MySQL, cliente sin option files y TCP a 127.0.0.1:3306
DENTRO del contenedor, con usuario/base fijos del laboratorio. No usa 13430 ni
otra dirección externa para cargar datos. La contraseña se toma dentro de ese
contenedor; no se imprime. Un desajuste detiene la acción, sin fallback.

Se leen los SQL originales en UTF-8, sin copiarlos, y se concatenan en el mismo
orden que Isabela: catalogo-local.sql y luego fichas-productos-local.sql en una
única invocación mysql. Sus hashes revisados están fijados en control.py; si los
originales cambian, la revisión normal se detiene hasta evaluar el cambio. No se
usa --force y no se imprime la salida SQL. Cada archivo tiene su propia transacción:
si falla el segundo, el primer COMMIT ya puede haber persistido. No son un único
todo-o-nada, igual que en el PowerShell. No ejecutar dos cargas simultáneas.

| SQL | Efectos y repetición |
| --- | --- |
| catalogo-local.sql | INSERT de diez productos ausentes por nombre/tienda 1. No actualiza precios, stock o estado de los existentes. Repetido secuencialmente no añade los mismos productos; no elimina duplicados previos ni garantiza ausencia de carreras entre cargas concurrentes. |
| fichas-productos-local.sql | INSERT IGNORE de marca, atributos y valores. UPDATE de descripción solo si está vacía, nula o conserva el texto anterior; asigna marca solo si falta. Inserta relaciones de atributos solo cuando el producto no tiene ya ese atributo. Conserva los valores ya editados; no cambia precios, stock o estado. Repeticiones secuenciales son conservadoras, sujetas a las restricciones únicas del esquema; no es un reparador de inconsistencias preexistentes. |

No hay USE, referencias a otras bases, DROP, TRUNCATE ni DELETE en estos originales
revisados. Sí hay INSERT/UPDATE: este procedimiento NO es de solo lectura cuando
se ejecute en una etapa futura autorizada.

### Qué reproduce y qué no reproduce

1. **Cuatro redes:** ambos laboratorios reutilizan los manifiestos/plantilla reales.
2. **Perfil local y catálogo:** normal reproduce local, runners, readiness, orden
   de SQL y UTF-8, con destinos estrictamente aislados.
3. **PowerShell completo:** pendiente. No se valida su lectura de .env, interacción
   de credenciales, herencia de contexto/proyecto, pipeline de PowerShell, Windows,
   restauración de variables ni propagación de errores del script real. El wrapper
   separa build/start/seed y añade defensas de destino que el script real no tiene.
4. **V39 + V40:** pendiente; V39 todavía no existe en la copia local.
5. **Actualización desde V32:** pendiente; la base de este laboratorio será nueva.

### Limpiar (NO ejecutado, autorización separada)

```bash
python3 validation/cu08-topology/control.py normal clean
python3 validation/cu08-topology/control.py demo clean
```

Cada clean valida propiedad y ejecuta down --volumes solo para SU proyecto. Borra
la base desechable de ese laboratorio, no imágenes ni cachés. No usa prune,
remove-orphans ni redes externas. No actuar sobre los manifiestos desde la raíz
sin el wrapper. No hay limpieza automática después de fallos.

## Comprobaciones reales posteriores, pendientes de autorización

Guardar únicamente evidencia saneada, sin cuerpos HTTP, contraseñas, cookies ni
tokens. Obtener credenciales solo de las fixtures de la base NUEVA; comprobar
cuenta activa y email verificado antes de probar login. Si faltan, detenerse y
preparar un seed exclusivo autorizado; nunca consultar otra base como fuente.

1. Confirmar salud y publicación EFECTIVA de los tres puertos exactamente en
   127.0.0.1, con los números de la tabla. Comparar recursos ajenos antes/después.
2. Resolver backend-login-proxy desde Nginx: esperar únicamente 172.30.238.11 o
   172.30.239.11. Confirmar origen privado .10 de la conexión Nginx -> backend.
3. Confirmar backend -> mysql:3306 por database y readiness UP. Desde el host,
   conectar al puerto 13430/13431 y ejecutar SELECT DATABASE(): debe ser la base
   exclusiva correcta. Guardar solo el booleano de coincidencia, no credenciales.
4. Consultar flyway_schema_history en esa base: V1-V38 y V40 exitosas, sin fallos.
   Demo incluye R__demo_fixture.sql. Revisar metadatos de login_attempts y sus
   cero registros; esto NO activa H02.
5. Verificar en runtime, sin volcar el entorno, enabled=false y origin-verified=false.
6. HTTP real con cookie jar en memoria: GET /api/auth/csrf, POST /api/auth/login
   con token válido -> 204, GET /api/auth/me -> 200. Recargar navegador y conservar
   sesión. CSRF inválido -> 403. POST /api/auth/logout con CSRF válido -> 204,
   /me -> 401 y cookie anterior rechazada. No deshabilitar CSRF ni usar mocks.
7. Abrir portada, catálogo y un detalle disponible de la fixture, navegación y
   panel de cuenta. Diferenciar vacío legítimo de error de transporte. Revisar
   que los accesos de compra no interfieran con el panel de cuenta.

## Alcance que NO queda acreditado

- No hay ejecución de estos laboratorios en esta etapa.
- El código local no tiene V39 de favoritos. No se acredita V39 + V40 juntas.
- Una instalación nueva NO acredita la actualización desde la demo V32. Eso
  requiere otra prueba sobre una base desechable representativa y un rollback
  ensayado, antes de tocar la demo real.
- No se valida Windows/Marketplace.exe, HTTPS por túneles, Swarm ni producción.
- No se resuelve la ausencia de .env.demo real ni el conflicto de 8080. No abrir
  el launcher para estas pruebas ni regenerar credenciales sobre su volumen.
- Las subredes fijas de proxy no pueden coexistir con otros proyectos que ya
  utilicen esos rangos. El preflight detiene ese caso; no altera redes ajenas.
