# Mantener la sesión iniciada (CU-08)

El formulario de login envía `rememberMe=true` únicamente cuando el usuario selecciona
«Mantener la sesión iniciada». La opción comienza desmarcada. `false`, ausencia del campo
u otros valores conservan el login normal.

| Modalidad | Inactividad permitida en JDBC | Cookie SESSION |
| --- | --- | --- |
| Normal | 30 minutos (`spring.session.timeout`) | De sesión, sin Max-Age/Expires |
| Mantener sesión | 7 días (`app.session.persistent-timeout`) | Max-Age de 604800 segundos y Expires |

La política se aplica exclusivamente después de autenticar contraseña, estado activo y
correo verificado. El navegador no puede elegir una duración arbitraria. El servidor
rechaza configuraciones sin vencimiento o con duración persistente menor o igual a la normal.

Se conserva Spring Security + Spring Session JDBC y una única credencial: la cookie SESSION.
No hay JWT, cookie adicional de remember-me ni reautenticación automática tras revocar la sesión.
`MAX_INACTIVE_INTERVAL` y `EXPIRY_TIME` en SPRING_SESSION reflejan la duración elegida;
el atributo que controla Max-Age se guarda en SPRING_SESSION_ATTRIBUTES. No hace falta migración.

La expiración JDBC es por inactividad: cada acceso la actualiza. La cookie persistente vence
7 días después de emitirse; las consultas ordinarias no renuevan su Max-Age. El acceso requiere
tanto una cookie disponible como una sesión vigente en JDBC. El cierre del navegador normalmente
descarta las cookies de sesión, aunque algunos navegadores pueden restaurarlas; el límite de
inactividad normal sigue aplicándose en el servidor.

Un nuevo login sin marcar la opción restablece la duración normal y emite cookie de sesión,
incluso si el navegador tenía una sesión persistente. La preferencia afecta únicamente a esa sesión.
`AuthService.restaurar()` recupera ambas modalidades mediante `/api/auth/me`.

Se mantienen HttpOnly, SameSite=Lax, Secure en HTTPS, rotación del identificador al autenticar,
CSRF y autorización por rol. Logout elimina la cookie y la sesión JDBC en ambos casos;
revocar una sesión o cambiar la contraseña también invalida las sesiones persistentes correspondientes.

Las pruebas `PersistentSessionIntegrationTests` usan cookies reales de los filtros, CSRF y MySQL
Testcontainers. Comparan cookies y tiempos guardados y simulan inactividad en la BD de pruebas,
sin esperar minutos o días. La prueba de navegador comprueba la opción, el parámetro enviado y
la restauración de la UI; utiliza una API controlada.
