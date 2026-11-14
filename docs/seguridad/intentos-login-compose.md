# CU-08: preparación del origen en Compose local

H02 continúa desactivado (`enabled=false`, `origin-verified=false`). Este documento
no autoriza migraciones sobre bases existentes ni acredita una prueba real del proxy.

## Cadena preparada

Navegador -> puerto loopback del frontend -> Nginx -> red `login_proxy` -> Tomcat
`RemoteIpValve` -> Spring Security -> guard -> contador JDBC.

`compose.yaml` asigna 172.30.238.10 a Nginx y 172.30.238.11 al backend.
La demo, también utilizada por Marketplace.exe, usa 172.30.239.10 y .11.
Solo la dirección .10 correspondiente es proxy confiable. No se confía en la
subred completa. La demo usa `!override` para sustituir la subred: requiere Docker
Compose 2.24.4 o posterior. Antes de crear las redes se debe comprobar que no
colisionan con redes Docker, LAN o VPN existentes. No se han recreado aquí.

## Conectividad de Compose normal y Marketplace.exe

La topología conserva los puertos configurables, siempre publicados explícitamente
en 127.0.0.1: frontend 4300, backend 8080 y MySQL 3307 por defecto. El backend
ejecutado desde el host conserva localhost:3307; dentro de Compose usa mysql:3306.
No se modifican Marketplace.exe, sus credenciales ni el volumen mysql_data.

| Red | Tipo | Miembros |
| --- | --- | --- |
| web_entry | bridge no interna | frontend y backend |
| login_proxy | bridge interna | frontend y backend, con sus IP fijas |
| database | bridge interna | backend y MySQL |
| mysql_host | bridge no interna | únicamente MySQL |

Todas las redes pertenecen al proyecto Compose: no tienen nombre global ni son
externas. La demo hereda esta topología y conserva su subred privada diferenciada.
El alias backend-login-proxy existe SOLO en login_proxy y se pasa a Nginx mediante
BACKEND_UPSTREAM_HOST. Así el upstream no utiliza el nombre multirred backend y
su conexión mantiene como origen la IP privada de Nginx autorizada por Tomcat.
MySQL no participa en web_entry ni login_proxy.

Docker asignará las subredes de las tres redes sin IP fija, evitando las redes que
ya conoce. Eso no garantiza compatibilidad con LAN/VPN: revisar docker network
inspect y las rutas del equipo antes de desplegar y tras crear redes. Si hay una
colisión, detener el despliegue y preparar un override IPAM específico y revisado;
no cambiar las IP de confianza aisladamente. Las subredes fijas 172.30.238.0/24 y
172.30.239.0/24 también requieren esa revisión. Normal y demo no pueden usar a la
vez los mismos puertos del host; para coexistir hay que ajustar los puertos de
desarrollo sin modificar los previstos por Marketplace.exe.

mysql_host permite publicación local y también salida de MySQL: la base no tiene
aislamiento total de egress. Los procesos del host pueden intentar acceder a 3307;
conservar autenticación fuerte y no conectar otros servicios a esa red. El control
administrativo del daemon Docker no queda limitado por esta segmentación.

Aplicar estos cambios requerirá recrear frontend, backend y MySQL por sus nuevas
interfaces de red, con interrupción de la demo. Mantener el mismo proyecto, volumen
y credenciales; nunca usar down --volumes ni ejecutar el seed de un laboratorio.
Antes de autorizarlo hay que revisar el historial de Flyway de esa base: recrear
el backend con una imagen nueva puede ejecutar migraciones al arrancar. Estos
cambios de archivos no recrean servicios ni autorizan migraciones sobre bases
persistentes. La publicación efectiva, DNS privado, sesiones, CSRF, login y logout
necesitan una regresión real posterior; la revisión estática no los acredita.

## Manejo de cabeceras y política H02

La plantilla montada `frontend/nginx.compose.conf` sustituye X-Forwarded-For y
X-Real-IP por el par TCP observado por Nginx, elimina Forwarded y deriva el esquema
de la conexión. La plantilla de la imagen utilizada por Swarm no cambia.
Un túnel HTTPS externo no forma parte de esta configuración local directa.

El backend usa `native`, la regex exacta del único proxy y el perfil incluido
`compose-local`. La política `allowed-local-origins` solo acepta direcciones
numéricas individuales de loopback o privadas y requiere ese perfil. Inicialmente
solo contiene 127.0.0.1 y ::1. No confiere confianza de proxy a esos orígenes y
no permite que la dirección del propio Nginx sea usada como cliente.

Docker Desktop podría presentar una dirección de gateway en lugar de loopback.
Hasta verificarla, el guard la rechazará cuando se habilite. Si es legítima,
configurar su dirección exacta mediante
`APP_AUTH_LOGIN_ATTEMPTS_ALLOWED_LOCAL_ORIGINS` en el entorno del backend;
no agregar rangos ni direcciones arbitrarias para evitar el rechazo.

El puerto directo del backend sigue publicado solo en loopback. Con H02 activo,
un login que no haya atravesado el proxy autorizado debe devolver 503 sin contar
intentos. El acceso administrativo al daemon Docker queda fuera de esta frontera:
quien lo controla puede sustituir contenedores y configuración.

## Validación pendiente antes de habilitar

Usar un entorno aislado y una base desechable autorizada, con las migraciones
integradas. No utilizar los volúmenes persistentes cuyo historial está pendiente.

1. Comprobar en Compose y demo la IP efectiva del par Nginx -> Tomcat y la IP
   final interpretada por RemoteIpValve. Registrar solo metadatos, nunca claves.
2. Habilitar explícitamente el mecanismo solo en ese entorno de validación.
   Confirmar que cinco fallos persisten una espera de 900 segundos, que la sexta
   petición devuelve 429/Retry-After y que repetirla no extiende el bloqueo.
3. Repetir enviando valores falsos y múltiples valores en X-Forwarded-For,
   X-Real-IP y Forwarded. No deben cambiar la clave correo + IP efectiva.
4. Usar dos orígenes realmente distintos: los fallos de A para un correo no deben
   bloquear B. Si Docker/NAT los representa por una misma IP, documentar que
   comparten contador; no afirmar que existe aislamiento por persona.
5. Probar acceso directo al backend con cabeceras falsas: debe rechazarse sin
   consultar credenciales ni modificar contadores. Probar CSRF ausente/inválido.
6. Confirmar éxito legítimo, reinicio del contador, ventana móvil, expiración y
   persistencia/reinicio en el entorno aislado. Reejecutar las integraciones JDBC
   existentes, además de las pruebas unitarias de la política.

`LoginAttemptOriginPolicyTests` prueba la política y la clave utilizada después
de resolver el origen; utiliza solicitudes simuladas y repositorio simulado.
`LoginAttemptHttpIntegrationTests` también simula el atributo de Tomcat mediante
MockMvc. Ninguna de estas pruebas demuestra el recorrido real Nginx/RemoteIpValve.

Solo tras conservar esa evidencia se podrá decidir la activación del despliegue
local. Swarm y los túneles necesitan su propia revisión y siguen fuera del alcance.
