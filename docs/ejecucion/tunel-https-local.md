# Acceder por túnel HTTPS al Marketplace local

La configuración Compose base autoriza los orígenes locales. Nginx local no confía en el esquema reenviado por clientes externos. Por eso una petición de inicio de sesión desde un túnel HTTPS puede recibir `403 Invalid CORS request` aunque las credenciales sean correctas.

Usar la configuración opcional `compose.tunel.yaml` para añadir el origen público exacto. No se desactivan CSRF, cookies de sesión ni las restricciones del proxy y no se usa un comodín para todos los túneles.

1. Mantener el túnel abierto y comprobar que el Marketplace carga.
2. En `.env`, definir el origen generado, **sin barra final, ruta o espacios**:

   ```dotenv
   MARKETPLACE_PUBLIC_ORIGIN=https://nombre-generado.trycloudflare.com
   ```

3. Mantener las variables de Mercado Pago con sus destinos correspondientes: retorno al frontend y notificación a `/api/payments/mercadopago/webhook`. El origen CORS es una configuración distinta, aunque comparta el dominio.
4. Desde la raíz, si también se utilizan notificaciones push:

   ```powershell
   docker compose --env-file .env --env-file .env.push -p marketplace-pruebas -f compose.yaml -f compose.notificaciones.yaml -f compose.tunel.yaml up -d backend frontend rabbitmq
   ```

   Si también se utiliza el perfil de pagos demo, añadir `-f compose.pagos-demo.yaml` antes de `up`. Conservar los archivos de configuración que correspondan al entorno. Para cambios de código, añadir `--build`.

5. Iniciar sesión desde el enlace HTTPS. La sesión de localhost no se comparte con el dominio público.

Al reiniciar el túnel con otro dominio, actualizar este origen, las URLs de pagos y el webhook del panel del proveedor, y recrear el backend. No es necesario cambiar contraseñas por un rechazo CORS.

Esta configuración autoriza peticiones de un origen; no autentica usuarios, no cambia destinatarios del dinero y no prueba HTTPS entre Nginx y backend. El túnel expone el entorno de desarrollo: usar cuentas de prueba y mantenerlo solo durante la demostración.
