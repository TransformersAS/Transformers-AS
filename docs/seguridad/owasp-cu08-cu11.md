# Verificación acotada de seguridad: CU-08 y CU-11

Fecha: 9 de noviembre de 2026. Base: `f06e3274`, más el refuerzo y pruebas
sin commit descritos aquí. No es una certificación OWASP, una auditoría de todo
el Marketplace ni un criterio de aprobación de la asignatura. No se dispone de
una rúbrica local que especifique versión de Top 10, nivel ASVS o umbral ZAP.

## Referencias oficiales y alcance

- [Autenticación](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html): mensajes genéricos y protección de credenciales.
- [Sesiones](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html): cookies, rotación, expiración e invalidación.
- [Autorización](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html): comprobaciones de permisos y propiedad en cada operación.
- [CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html): protección de operaciones que cambian estado.

## Matriz de implementación y verificación

Los resultados PASS se limitan a las aserciones de las suites indicadas; no
demuestran ausencia de todas las vulnerabilidades de una categoría OWASP.

| Control | Implementación | Pruebas ejecutadas | Resultado |
| --- | --- | --- | --- |
| Autenticación y autorización por rol activo | Spring Security y AccountPrincipal | SessionAuthenticationTests (37), BuyerIsolationIntegrationTests (14) | PASS |
| Rotación, cookies y expiración de sesiones | LoginSessionPolicy y Spring Session JDBC | PersistentSessionIntegrationTests (33) | PASS; HTTPS real pendiente |
| Revocación y permisos vigentes | ManageAccountSessions y CurrentAccountSessionFilter | CurrentAccountSessionIntegrationTests (16), SessionAuthenticationTests | PASS |
| Autorización dentro del caso de uso, además del filtro HTTP | FindOwnOrders ahora exige COMPRADOR activo antes de acceder a datos | FindOwnOrdersAuthorizationTests (5 casos nuevos) | PASS |
| Propiedad de pedidos y cancelaciones | Consulta por identidad de sesión y RequestOrderCancellation | OrderOwnershipIntegrationTests (24) | PASS |
| Protección de historial y PDF | Autorización antes de acceder al historial o generar el PDF | BuyerOrderHistoryAuthorizationTests (3), OrderReceiptAuthorizationTests (3), BuyerOrderReceiptTests (6) | PASS |
| CSRF en operaciones sensibles | Filtro Spring Security; sin deshabilitarlo para las pruebas | SessionAuthenticationTests, PersistentSessionIntegrationTests, BuyerIsolationIntegrationTests, OrderOwnershipIntegrationTests | PASS |
| Validación de filtros, motivos y contraseñas | Parsers, DTO y reglas de negocio existentes | BuyerOrderFilterTests (17), OrderOwnershipIntegrationTests, RecoverAccountPasswordValidationTests (9), SessionAuthenticationTests | PASS; no es una prueba exhaustiva de inyección |
| Recuperación neutral y manejo de fallos SMTP | RecoverAccountPassword y PasswordRecoveryController; tokens almacenados como hash | PasswordRecoverySmtpIntegrationTests (5), SessionAuthenticationTests | PASS |

## Cambio realizado

FindOwnOrders ya limitaba las consultas al propietario, pero dependía de la
autorización HTTP para exigir el rol comprador. Se añadió esa comprobación
también al caso de uso, utilizado únicamente en rutas de comprador de CU-11.
No se identificó un bypass HTTP: es defensa adicional para llamadas internas.

Los cinco casos nuevos rechazan vendedor, administrador, soporte, cuenta
multirrol sin selección y cuenta multirrol con vendedor activo. Comprueban
lista simple, lista filtrada, detalle, historial y PDF, sin tocar repositorios
ni generador PDF. Las pruebas existentes conservan los casos positivos.

## Ejecución y evidencia

Selección de 12 clases: **172 casos finalmente aprobados**, sin omisiones.
La primera ejecución terminó con 167 aprobados y cinco errores en la fixture
nueva: UserAccount rechazó un hash sin formato BCrypt. Se corrigió únicamente
esa fixture y se repitieron sus cinco casos, todos aprobados. No se presenta
la primera ejecución como BUILD SUCCESS.

Los casos Spring usan MySQL 8.4.11 nuevo de Testcontainers con reutilización
desactivada y Ryuk habilitado. SMTP usa GreenMail local; no correos reales.
No se ejecutó Compose, ZAP ni una llamada al proveedor real de pagos.

Reportes: `backend/demo/target/surefire-reports/TEST-*.xml` y resúmenes `.txt` de
las clases de la matriz. Son resultados generados, no archivos para este commit.
Las cantidades de una suite compartida entre controles no deben sumarse varias veces.

Para reproducir desde `backend/demo`, con Java 21, Docker local y dependencias
disponibles, usar un entorno sin propiedades heredadas de bases externas:

```bash
env -i HOME="$HOME" PATH="$PATH" \
JAVA_HOME=/Users/sofiamantilla/Library/Java/JavaVirtualMachines/temurin-21.0.5/Contents/Home \
DOCKER_HOST="unix://$HOME/.docker/run/docker.sock" \
TESTCONTAINERS_REUSE_ENABLE=false TESTCONTAINERS_RYUK_DISABLED=false \
./mvnw -o -Djacoco.skip=true \
'-Dtest=FindOwnOrdersAuthorizationTests,OrderReceiptAuthorizationTests,BuyerOrderHistoryAuthorizationTests,RecoverAccountPasswordValidationTests,SessionAuthenticationTests,PersistentSessionIntegrationTests,CurrentAccountSessionIntegrationTests,BuyerIsolationIntegrationTests,OrderOwnershipIntegrationTests,BuyerOrderFilterTests,BuyerOrderReceiptTests,PasswordRecoverySmtpIntegrationTests' test
```

La ruta Java y el socket corresponden al equipo de esta ejecución; en otro
equipo hay que seleccionar su JDK 21 y daemon local, sin apuntar a servicios ajenos.

## Pendientes y límites

- Confirmar el criterio académico: versión, alcance y evidencias exigidas.
- Verificar HTTPS, cookie Secure y cadena de proxy en el despliegue real;
  las pruebas de solicitudes seguras simuladas no acreditan ese despliegue.
- La protección de intentos sigue desactivada por defecto; esta ejecución no
  acredita su activación ni la modifica.
- No se ejecutó ZAP ni un análisis completo de XSS, inyección o dependencias.
- Si se solicita ZAP, acordar antes el objetivo aislado y la modalidad. Un
  análisis pasivo de tráfico autenticado no equivale a un análisis activo ni
  acredita todos los roles. Nunca dirigir el análisis a la demo compartida.
- La recuperación conserva deliberadamente una respuesta neutral ante SMTP;
  la aceptación funcional de esa interpretación de A11 sigue siendo separada.

No se declara cumplimiento del 100 % de OWASP ni del proyecto.
