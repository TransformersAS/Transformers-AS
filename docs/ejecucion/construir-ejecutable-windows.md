# Construcción y validación de la entrega Windows

El profesor sigue [iniciar-demo-windows.md](iniciar-demo-windows.md). Solo necesita Docker Desktop
con Linux containers. .NET SDK 8 es necesario únicamente para **construir** el launcher.

Desde la raíz del repositorio, en macOS o Windows con SDK 8:

```text
dotnet publish launcher/Marketplace/Marketplace.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o .
```

Produce `Marketplace.exe` en la raíz. El ejecutable incluye el runtime y extrae sus bibliotecas
nativas al directorio temporal de Windows. No distribuir solo el exe: entregar también
`compose.yaml`, `compose.demo.yaml`, `Cerrar Marketplace.cmd`, `frontend/` y `backend/demo/`,
incluidos los archivos ocultos de construcción (`.mvn`, `.dockerignore`, etc.). No empaquetar
`.env`, `.env.demo`, `.git`, `node_modules`, `target`, registros ni bases de datos de desarrollo.

El workflow `.github/workflows/build-marketplace-launcher.yml` se ejecuta manualmente con
`workflow_dispatch`, usa Windows y .NET 8 y publica **Marketplace-win-x64**, cuyo contenido es
`Marketplace.exe`. Copiarlo junto a `compose.yaml` en la entrega. No ejecuta despliegues.

## Configuración y ciclo de vida

El launcher busca la raíz desde la ubicación del ejecutable, nunca desde el directorio actual.
Pasa argumentos a Docker mediante `ProcessStartInfo.ArgumentList`, sin intérprete de comandos.
Verifica CLI, motor Linux y Compose. Un bloqueo de archivo impide arranques simultáneos.

Crea `.env.demo` mediante escritura atómica con dos secretos aleatorios independientes de
256 bits. Si no existe `.env`, crea también ese archivo; si existe, lo conserva. Las ejecuciones
siguientes reutilizan `.env.demo`. Las variables heredadas del proceso no sustituyen sus
contraseñas. El proyecto fijo `marketplace-demo` usa su propio volumen `marketplace-demo_mysql_data`,
base `marketplace_demo` y usuario `marketplace_demo`. No borrar `.env.demo` conservando el volumen:
MySQL solo aplica sus contraseñas de inicialización cuando el volumen está vacío.

Ejecuta Compose con ambos archivos, primero MySQL, después backend y frontend, con `up -d --build`
y `--wait`. Recrea el backend para ejecutar el provisioning en cada apertura y el frontend para que Nginx
resuelva la dirección actual del backend.
El backend espera readiness (incluye MySQL y finalización del runner). Finalmente comprueba
`/healthz` y `/api/actuator/health/readiness` desde la URL del navegador. La compilación tiene
un límite de 45 minutos por etapa, y se informa progreso cada 30 segundos. Los detalles van a
`launcher-logs/marketplace.log`, con las contraseñas generadas ocultas.

El código C# no usa Bash, curl, Git, Maven, Java ni Node en Windows. Las imágenes existentes
contienen sus herramientas de construcción y healthchecks. El cierre invoca `compose stop`;
los datos y las imágenes se conservan. No hay `down -v` en el launcher.

## Datos reales y reset acotado

`DemoProvisioning` solo existe con el perfil `demo`; `local` y producción conservan su comportamiento.
Flyway carga además `db/demo/R__demo_fixture.sql`, que guarda un único marcador de pedido.
La migración repetible no adelanta la versión de las migraciones normales.

El runner transaccional bloquea el marcador, crea las dos cuentas con el `PasswordEncoder`
BCrypt existente, verifica sus correos y asigna al vendedor la tienda principal. Si una cuenta
preexistente tiene otras credenciales/roles, falla sin reemplazarla silenciosamente.

La primera vez crea una camiseta de $25.000, stock 100, una dirección del comprador y una compra
mediante `CartService`, `InventoryReservationService` y `ProcessPaymentUseCase`. Resultan un pedido
CONFIRMED por $35.000 (incluye envío), pago APPROVED, transacción, snapshot, línea, historial,
reserva confirmada y stock 99. Pago y reembolso usan los simuladores ya existentes del proyecto;
autenticación, CSRF, autorización y persistencia no se sustituyen.

En otro inicio, si el mismo pedido está CONFIRMED no se duplica. Si está CANCELLED, se resta
exactamente el inventario que su cancelación repuso, se limpian exclusivamente sus registros
de cancelación, reembolso, notificaciones e historial de estados y se restaura CONFIRMED/APPROVED.
Se mantiene su identidad y la auditoría; el nuevo historial identifica la restauración académica.
Los demás pedidos no cambian. El reset es una restauración del fixture, no un nuevo estado o
transición de negocio. Rechaza otros estados, envíos existentes o falta de stock; todo revierte
si falla. No usar este perfil sobre una base de producción.

## Pruebas reproducibles

```text
cd backend/demo
./mvnw -Dtest=DemoProvisioningIntegrationTests,LocalProvisioningIntegrationTests clean test
```

El primer test arranca MySQL limpio y prueba cuentas verificadas, roles, sesiones, logout,
propiedad, CSRF, OTHER sin explicación, cancelación, reembolso y lectura persistida. Repite
cancelación/reset y crea otro pedido real para comprobar que no se modifica. El segundo
comprueba que el perfil local anterior conserva su comportamiento. La clase demo también
está incluida en el manifiesto de integración existente; no se cambia ningún umbral de cobertura.

Con Compose demo recién iniciado (el test consume su pedido):

```text
cd frontend
npx playwright test --config=playwright.demo.config.ts
```

`DEMO_BASE_URL` permite usar un puerto aislado de validación. El test de Chrome ejecuta el guion
CU-08/CU-11 sin interceptar ni simular respuestas: comprueba ambos roles, sesión actual, invalida
la sesión mediante logout, entra como comprador, cancela con Otro, comprueba reembolso,
recarga y verifica que no aparece Cancelar pedido. Reiniciar el launcher prepara otra ejecución.

Para probar manejo de infraestructura del launcher portable desde macOS/Linux:

```text
python3 launcher/tests/smoke.py /ruta/al/dotnet
```

Solo esta prueba usa un CLI Docker falso: valida rutas con espacios, directorio actual ajeno,
Docker ausente/apagado/sin Compose, errores, generación y conservación de secretos y `.env`.
No simula backend ni MySQL en la demo o en las pruebas de sus funcionalidades.

Validado en macOS: compilación .NET sin advertencias, publicación PE32+ win-x64 con runtime,
smoke del launcher, YAML, Compose con volumen limpio, integración MySQL y E2E real en Chrome.
Pendiente en Windows: doble clic nativo, extracción del runtime sin SDK instalado, apertura del
navegador predeterminado, cierre `.cmd`, mensajes ante Docker Desktop apagado y ejecución del
workflow en GitHub. El exe no está firmado y puede mostrar SmartScreen. La primera descarga
y construcción requiere Internet; los puertos 4300, 8080 y 3307 deben estar libres.

`deploy.sh`, `stack.yml` y los despliegues Swarm no se modifican ni se invocan.
