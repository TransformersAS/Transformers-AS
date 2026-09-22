# Recuperación de contraseña por SMTP (CU-08)

`RecoveryNotificationConfiguration.passwordRecoveryNotifier` ahora configura
`SmtpPasswordRecoveryNotifier` como adaptador predeterminado del puerto
`PasswordRecoveryNotifier`. Se elimina el notifier vacío. Un adaptador alternativo
registrado como bean sigue teniendo prioridad mediante `ConditionalOnMissingBean`.

El correo contiene el token y las instrucciones para copiarlo en «Ya tengo un token de
recuperación» / «Token de recuperación recibido» en el panel de acceso existente.
No hace falta modificar el frontend ni incluir el token en una URL.

## Configuración externa

Configurar en el entorno de ejecución (o en `.env` si se usa el Compose del repositorio):

```dotenv
SPRING_MAIL_HOST=smtp.example.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=marketplace
SPRING_MAIL_PASSWORD=<secreto-del-proveedor>
PASSWORD_RECOVERY_FROM=recovery@example.com
```

El remitente debe estar autorizado por el proveedor SMTP. Se reutilizan `JavaMailSender`
y las propiedades SMTP de Spring Boot ya existentes: autenticación y STARTTLS obligatorio,
con timeouts de conexión, lectura y escritura de 5 segundos. `.env.example` documenta las
variables; `compose.yaml` las pasa al backend. No se guardan credenciales reales en Git.
Para ejecutar Spring Boot fuera de Compose, exportar esas variables al proceso.

`PASSWORD_RECOVERY_FROM` configura `app.password-recovery.from`. Es independiente de
`EMAIL_VERIFICATION_FROM`; se pueden configurar ambos con la misma dirección autorizada,
pero ninguno se usa como fallback del otro. La verificación conserva su adaptador,
asunto, endpoints, tabla de tokens y vencimiento de 30 minutos. La recuperación usa
`password_recovery_tokens` y vence en 15 minutos. Ningún token sirve en el otro flujo.

## Seguridad y fallo de entrega

Se conserva `RecoverAccountPassword`: genera 32 bytes aleatorios (256 bits), codificados
como 43 caracteres base64url, guarda solamente SHA-256 y envía después del commit.
La confirmación usa el token una sola vez, valida vencimiento y estado de cuenta,
cambia el hash BCrypt de la contraseña e invalida sus sesiones. Una solicitud nueva
invalida los tokens anteriores.

La respuesta de `/api/auth/password-recovery/request` sigue siendo 202 con el mismo
mensaje para cuentas activas, inexistentes, inactivas y fallos SMTP. El token nunca forma
parte de esa respuesta. Devolver 503 solo para cuentas existentes permitiría enumerarlas;
por eso no se traslada la política de errores de verificación a recuperación.

Si falta SMTP o el remitente, el adaptador falla explícitamente en lugar de simular una
entrega. El caso de uso captura ese fallo igual que los errores SMTP y registra únicamente
`Password recovery notification could not be delivered`, sin token, destinatario ni
excepción del proveedor. No se registran cuerpos de correo; no se debe activar el debug
de protocolo SMTP, que podría mostrar mensajes y credenciales.

El envío conserva la ejecución síncrona posterior al commit. No hay cola ni reintentos
automáticos. Si la entrega falla, el hash ya guardado conserva su vencimiento de 15 minutos;
el usuario puede solicitar otro código. Esto también evita invalidar un código que el
servidor SMTP haya aceptado aunque se haya perdido su respuesta. El 202 es una respuesta
genérica de solicitud, no una garantía de entrega en el buzón final.

## Pruebas

`PasswordRecoverySmtpIntegrationTests` usa MySQL/Testcontainers, Spring Security y
GreenMail por SMTP en loopback: contenido y destinatario correctos, hash en persistencia,
ausencia de token en HTTP/logs, cambio de contraseña, invalidación de sesiones, respuestas
no reveladoras, caída de SMTP y reintento, tokens inválidos/vencidos/usados/reemplazados y
separación entre verificación y recuperación. TLS y autenticación se desactivan únicamente
para el servidor de pruebas local.

`RecoveryNotificationConfigurationTests` cubre el adaptador predeterminado, configuración
faltante, remitente independiente y sustitución mediante el puerto.

Validación ejecutada:

```sh
mvn -q -f backend/demo/pom.xml -Dtest=PasswordRecoverySmtpIntegrationTests,RecoveryNotificationConfigurationTests,RecoverAccountPasswordValidationTests,SessionAuthenticationTests,EmailVerificationIntegrationTests,SmtpEmailVerificationNotifierTests test
docker compose config --quiet
git diff --check
```

Resultado: 66 pruebas aprobadas (5 SMTP de recuperación, 4 de configuración, 9 de
validaciones de recuperación, 37 de autenticación/sesiones, 9 de verificación y 2 de su
adaptador SMTP), sin fallos ni omitidas. Compose y diff sin errores. No se cambió frontend.
