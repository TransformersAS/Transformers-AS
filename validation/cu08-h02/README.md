# Laboratorio reproducible CU-08 H02

Este laboratorio valida la protección de login con HTTP real a través de Nginx,
Tomcat y MySQL: cinco fallos en quince minutos y una espera de quince minutos.
Es independiente de la demo, Swarm y el laboratorio de topología. No utiliza
Marketplace.exe ni el script de entrega y no se combina con los Compose raíz.

H02 permanece desactivado por defecto en los despliegues normales. El manifiesto
base también inicia con `enabled=false` y `origin-verified=false`. Solo el override
`activate.yaml` permite activarlo aquí, después de observar y confirmar el origen.
Estas instrucciones no autorizan migraciones sobre bases antiguas ni activación
en otros despliegues. No se ejecutó el laboratorio al actualizar este documento.

## Resultado histórico

Las evidencias conservadas documentan estos resultados aprobados:

| Escenario | Resultado observado |
| --- | --- |
| Login legítimo con CSRF válido | 204; sesión autenticada 200 |
| Cinco fallos del mismo correo/origen | Cinco 401 dentro de 1,708 segundos |
| Siguiente intento | 429, Retry-After 900 |
| Duración persistida | 900000 ms desde el quinto fallo |
| Solicitudes durante bloqueo | No extienden ni cambian la fecha límite |
| Reinicio exclusivo del backend | Mismo contador; 429 y Retry-After 869 |
| X-Forwarded-For, Forwarded y X-Real-IP falsificados | No alteran la clave ni evaden el bloqueo |
| Acceso directo con CSRF válido y cabeceras falsas | 503 sin modificar contadores |
| CSRF inválido | 403 sin modificar contadores |
| Flyway | V40 aplicada en cu08_h02_validation |

Se verificó proxy `172.30.240.10` y origen efectivo `172.30.242.1`, gateway NAT
de Docker Desktop. El primer arranque con redes solo internas no publicaba los
puertos; la red de entrada corrigió ese problema y permitió las pruebas reales.
La corrección ya se aplicó durante aquella validación.

Al terminar, H02 quedó activo SOLO en el laboratorio y sus recursos se conservaron.
Esto describe evidencia histórica, no una inspección del estado actual. Antes de
operar hay que identificar nuevamente recursos, configuración y propietarios.

Pendientes reales:

- Esperar quince minutos completos y demostrar el desbloqueo.
- Comprobar los límites de la ventana móvil con fallos antiguos.
- Separar dos orígenes independientes: localhost fue unificado por NAT.
- Probar CSRF ausente por separado del token inválido.
- Otros equipos Docker Desktop, Swarm, producción y túneles HTTPS.
- Integración conjunta V39 de favoritos + V40 y actualización de bases antiguas.

Las pruebas internas que simulan tiempo o el atributo de Tomcat complementan,
pero no sustituyen, los escenarios reales pendientes. Cambiar cabeceras desde
localhost no demuestra separación de clientes.

## Código reproducible y resultados

Versionar como herramientas:

```text
compose.yaml
activate.yaml
nginx.conf
seed.sql
control.sh
README.md
evidencias/ejecutar_escenarios.py
```

El `.py` es código ejecutable aunque esté bajo `evidencias/`. Los JSON, logs y
conclusiones son resultados históricos: seleccionarlos y revisar su saneamiento
en otro cambio, sin añadir automáticamente toda la carpeta. El modo
`after-restart` requiere el JSON generado por `before-restart` en esa ejecución.

## Requisitos y dependencias

- Checkout completo de Transformers-AS, manteniendo esta carpeta en su ubicación:
  los builds usan `../../backend/demo` y `../../frontend`. Deben existir el guard,
  repositorio JDBC, perfil `compose-local` y migración V40.
- Docker Desktop con daemon Linux local, contexto llamado `desktop-linux` y
  endpoint de socket Unix. El wrapper rechaza endpoints de otro tipo.
- Docker Compose v2 con soporte de `name`, límites CPU/memoria y `up --wait`.
- Bash, Python 3 sin optimización (`-O`/`PYTHONOPTIMIZE`), curl y sed. Python usa
  solo su biblioteca estándar; el runner depende de sus aserciones.
- Acceso para descargar imágenes/dependencias durante builds. Registrar SHA del
  checkout e imágenes: el laboratorio construye el código disponible y no fija
  una revisión productiva ni digests de imágenes por sí mismo.
- Recursos para frontend 0.25 CPU/128 MiB, backend 1 CPU/1536 MiB y MySQL
  0.75 CPU/768 MiB. Los builds son secuenciales, sin esos límites de runtime.

El wrapper limpia variables heredadas y usa `--env-file /dev/null`; no carga
`.env` ni `.env.demo`. Conserva PATH y HOME para herramientas/configuración Docker.
No necesita rutas absolutas de un equipo. Las claves de MySQL y la cuenta de
`seed.sql`, incluido su password y BCrypt, son fixtures públicas desechables,
no secretos reales. Nunca reutilizarlas en demo o producción.

## Recursos e aislamiento

Proyecto fijo: `cu08-h02-20261107`. No admite dos copias simultáneas.

| Recurso | Configuración |
| --- | --- |
| Frontend | 127.0.0.1:14320; imagen cu08-h02-20261107-frontend:validation |
| Backend directo de prueba | 127.0.0.1:18320; imagen cu08-h02-20261107-backend:validation |
| MySQL | mysql:8.4.11, sin puerto publicado |
| Base | cu08_h02_validation |
| Volumen | cu08-h02-20261107_mysql_data |
| Entrada | cu08-h02-20261107_entry; 172.30.242.0/24; no interna; frontend/backend |
| Proxy | cu08-h02-20261107_login_proxy; 172.30.240.0/24; interna; frontend .10/backend .11 |
| Base de datos | cu08-h02-20261107_database; 172.30.241.0/24; interna; backend .11/MySQL .12 |

Nginx conecta a `172.30.240.11:8080`, conservando su IP privada `172.30.240.10`.
Sustituye cabeceras de origen, elimina Forwarded y deriva el esquema de su propia
conexión. Tomcat confía solo en la IP exacta del proxy.

No hay redes/volúmenes externos ni socket Docker montado. Sus nombres son
explícitos: cambiar solo `-p` NO crea una segunda instancia aislada. La red de
entrada permite salida de frontend/backend; no hay aislamiento total de egress.
Se comparten daemon, hardware y caché de builds. El administrador de Docker queda
fuera de esta frontera de confianza.

Antes de operar, revisar puertos, rutas LAN/VPN, subredes Docker y propiedad de
contenedores, redes, imágenes y volumen. El wrapper no detecta automáticamente
todas las colisiones ni comprueba identidad en todos sus comandos. No adoptar
recursos ajenos aunque coincidan los nombres. No alterar demo/Swarm para liberar
puertos o recursos.

## Inicio nuevo: observación con H02 apagado

Comandos para una ejecución futura autorizada, desde la raíz del repositorio.
No reutilizarlos sobre la base del laboratorio histórico.

```bash
bash validation/cu08-h02/control.sh config
bash validation/cu08-h02/control.sh build
bash validation/cu08-h02/control.sh observe-up
bash validation/cu08-h02/control.sh seed
```

`config` consulta Docker y valida Compose sin crear recursos; no es una revisión
offline. Usa salida quiet para no imprimir fixtures. `observe-up` rechaza redes,
volumen o contenedores preexistentes. Si falla, detenerse: no borrar recursos
para continuar sin revisar su propiedad.

El arranque ejecuta Flyway sobre la base nueva. Tras el healthcheck, el seed crea
la cuenta activa, verificada y compradora exclusivamente en cu08_h02_validation.
Es de un solo uso; no restablece cuentas existentes. No ejecutarlo nuevamente en
la base conservada ni usarlo en otros proyectos.

Observar peticiones sin intentos fallidos de login:

```bash
curl --silent --output /dev/null http://127.0.0.1:14320/api/auth/csrf
curl --silent --output /dev/null -H 'X-Forwarded-For: 8.8.8.8' -H 'X-Real-IP: 1.1.1.1' -H 'Forwarded: for=8.8.8.8;proto=https' http://127.0.0.1:14320/api/auth/csrf
curl --silent --output /dev/null http://127.0.0.1:18320/api/auth/csrf
bash validation/cu08-h02/control.sh origins
```

Correlacionar peticiones secuenciales: por Nginx, Tomcat debe mostrar el origen que
observa el proxy y `forwarded=true`; las cabeceras falsas no deben cambiarlo.
El acceso directo no debe obtener el atributo. Distinguir healthchecks y verificar
que solo Nginx ocupa la IP confiable. No guardar cuerpos CSRF, cookies ni tokens.
No activar si falta evidencia. No autorizar automáticamente la IP histórica en
otro equipo. En observación, H02 apagado no exige rechazar el login directo.

## Activación y escenarios automatizados

Solo después de verificar la cadena, definir en esa terminal:

```bash
export H02_VERIFIED_ORIGIN='<IP exacta observada>'
export H02_ORIGIN_CONFIRMED=true
bash validation/cu08-h02/control.sh config-active
bash validation/cu08-h02/control.sh activate
```

Sustituir el placeholder por la IP verificada. El wrapper admite una dirección
loopback o IPv4 RFC1918 individual, distinta del proxy. `activate` recrea solo
backend, sin build ni dependencias. Confirmar salud, flags e identidad de los
otros contenedores antes de continuar. No guardar confirmaciones en producción.

Precondiciones concretas del runner:

- Contexto desktop-linux, contenedor `cu08-h02-20261107-mysql-1`, base y puertos
  indicados. Verificar etiquetas y montajes antes de ejecutarlo.
- Cuenta leída de seed.sql y contador inicialmente vacío, sin bloqueos previos.
- Origen efectivo **172.30.242.1**, fijado en su comparación SQL. Si difiere,
  detenerse y revisar una adaptación posterior; no falsificar cabeceras ni
  editar contadores para hacer pasar pruebas.
- Ejecución exclusiva, sin otras solicitudes de login al fixture.
- Ausencia de before-restart.json y after-restart.json: rechaza sobrescribirlos.
  En este workspace ya existen; no volver a ejecutarlo ni borrar evidencias.
  Para reproducir, usar un checkout sin resultados generados y comprobar que no
  comparte recursos existentes. Los nombres fijos impiden otra copia simultánea.

En una ejecución nueva que cumpla esas condiciones:

```bash
python3 validation/cu08-h02/evidencias/ejecutar_escenarios.py before-restart
bash validation/cu08-h02/control.sh restart-backend
# Esperar readiness: si curl falla, no continuar y volver a comprobar salud.
curl --fail --silent --output /dev/null http://127.0.0.1:18320/actuator/health/readiness
python3 validation/cu08-h02/evidencias/ejecutar_escenarios.py after-restart
```

El reinicio y la segunda fase deben completarse antes de vencer el bloqueo.
`before-restart` produce los cinco fallos y comprueba HTTP, cabeceras, CSRF y el
contador mediante SELECT. `after-restart` compara con el JSON anterior y exige
429. El runner no reinicia, siembra ni cambia relojes o contadores por SQL; los
logins sí modifican el contador por el mecanismo normal. Tokens/cookies quedan
en memoria. Puede escribir informe FALLIDO antes de terminar con error: revisar
siempre su resultado. No repetirlo a ciegas sobre estado previo.

## Detener sin borrar y limpieza separada

El wrapper no tiene comando stop. Para detener exclusivamente el proyecto y
conservar el volumen, desde la raíz y tras verificar pertenencia:

```bash
env -i PATH="$PATH" HOME="$HOME" docker --context desktop-linux compose --env-file /dev/null --project-directory "$PWD/validation/cu08-h02" -p cu08-h02-20261107 -f "$PWD/validation/cu08-h02/compose.yaml" stop
```

No hay procedimiento automático de reanudación de recursos existentes.
`observe-up` no sirve para reanudarlos: los rechaza. Una reanudación debe revisar
el estado y preservar explícitamente la configuración activa u observación.

Solo cuando se autorice desechar la base y se hayan preservado las evidencias:

```bash
bash validation/cu08-h02/control.sh clean
```

`clean` comprueba etiquetas de las redes/volumen conocidos y usa el proyecto fijo
para `down --volumes`: elimina contenedores, redes y datos del laboratorio.
No borra evidencias del filesystem, imágenes ni cachés; no utiliza prune ni
remove-orphans. No sustituye la revisión previa de pertenencia. No hay limpieza
automática tras fallos. No limpiar el laboratorio conservado sin autorización.
