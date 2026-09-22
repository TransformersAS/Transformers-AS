# Verificación de correo (CU-08)

El login solo crea una sesión si la cuenta está activa y `email_verified_at` no es NULL.
Con contraseña correcta y correo pendiente devuelve 403 con `code: EMAIL_NOT_VERIFIED`.
Con contraseña incorrecta o correo inexistente conserva el 401 genérico.

El registro de vendedor envía un código al buzón después de guardar la cuenta y la tienda.
Si SMTP falla, responde 201 con `verificationDeliveryFailed: true`: la cuenta sigue pendiente
y la interfaz permite reintentar. No hay confirmación por nombre de tienda.

- `POST /api/auth/email-verification/resend`: `{ "email": "...", "password": "..." }`.
  Requiere credenciales correctas y CSRF, pero no una sesión autenticada. Devuelve 202;
  si no se puede entregar, 503 con un mensaje sin detalles de SMTP. Una cuenta ya verificada
  no recibe otro código. No inicia sesión.
- `POST /api/auth/email-verification/confirm`: `{ "token": "..." }` y CSRF.
  Devuelve 204; un token incorrecto, vencido, usado o reemplazado devuelve 400 con
  `INVALID_VERIFICATION_TOKEN`. La cuenta inactiva tampoco puede confirmarse.

El código tiene 256 bits aleatorios, caduca en 30 minutos y solo se guarda su hash SHA-256.
Un reenvío invalida los anteriores, incluso si el nuevo envío falla; se puede volver a intentar.
La confirmación y la concesión del rol vendedor de un registro pendiente son atómicas.
V31 elimina las sesiones antiguas de cuentas cuyo correo aún no estaba verificado.
No cambia las marcas de verificación existentes ni modifica migraciones ya aplicadas.

## Configuración SMTP

Configurar en el entorno de ejecución, sin guardar secretos en Git:

```text
SPRING_MAIL_HOST=<servidor SMTP>
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=<usuario SMTP>
SPRING_MAIL_PASSWORD=<secreto SMTP>
EMAIL_VERIFICATION_FROM=<remitente autorizado>
```

Se exige STARTTLS y autenticación de forma predeterminada; los timeouts de conexión,
lectura y escritura son de 5 segundos. Sin configuración no se simula un envío exitoso.
El usuario copia el código desde el correo a «Verificar correo» en el panel de acceso
o al formulario del registro de vendedor. Para reenviar desde el login debe proporcionar
correo y contraseña; así no se revela el estado de cuentas a terceros sin credenciales.

Las pruebas `EmailVerificationIntegrationTests` usan MySQL Testcontainers y GreenMail
SMTP en loopback, sin enviar correo a destinatarios externos. STARTTLS y autenticación
se desactivan únicamente en esas pruebas locales.
