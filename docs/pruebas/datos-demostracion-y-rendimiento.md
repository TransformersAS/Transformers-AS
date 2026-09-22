# Datos reproducibles de demostración y desempeño

**SOLO desarrollo, demostraciones y pruebas. Nunca producción ni una BD con datos importantes.**
El generador `scripts/performance-demo-seed.sh` utiliza MySQL real, sin modificar el backend,
las migraciones Flyway ni los seeds CU-20/CU-23 existentes. Su implementación SQL por lotes
está en `scripts/performance-demo-seed.py` (Python 3 sin dependencias externas).

La escala citada del documento de decisiones arquitectónicas (actualmente ausente
del repositorio) es de 100 usuarios concurrentes, 1.000 productos y analítica sobre 10.000 pedidos simulados.
100 usuarios virtuales concurrentes **no requieren técnicamente 100 cuentas diferentes**;
el pool facilita pruebas k6 con identidades, cookies y sesiones independientes. Crear estos
datos no demuestra por sí mismo que se cumplan los RNF: hay que ejecutar y medir la carga.

## Requisitos y ejecución

- Docker y Python 3; `htpasswd` local o acceso a la imagen `httpd:2.4` como alternativa.
- MySQL 8.4 con las migraciones **V1..V30** del repositorio ya aplicadas por Flyway.
- Una base **desechable**, sin tráfico del backend durante seed/reset. No usar las bases de
  Compose/Swarm que contengan datos importantes; especificar su contenedor de pruebas.
- Permisos de lectura/escritura y CREATE para la tabla auxiliar y tablas temporales.
- `DEMO_PASSWORD` y `DB_PASSWORD` obligatorias. La primera debe cumplir la política actual:
  mínimo 12 caracteres y máximo 72 bytes UTF-8, sin saltos de línea.

Ejemplo en Bash (no deja contraseñas en el historial):

```bash
read -rsp 'Contraseña de las cuentas demo: ' DEMO_PASSWORD; echo
read -rsp 'Contraseña MySQL de pruebas: ' DB_PASSWORD; echo
export DEMO_PASSWORD DB_PASSWORD
export MYSQL_CONTAINER=mysql-performance-test
export DB_NAME=marketplace_performance_demo
export DB_USER=marketplace_app
./scripts/performance-demo-seed.sh
unset DEMO_PASSWORD DB_PASSWORD
```

La base y el usuario deben existir previamente; el script no crea usuarios de MySQL ni
adivina credenciales. Los defaults son `MYSQL_CONTAINER=mysql-mkt`,
`DB_NAME=marketplace_performance_demo`, `DB_USER=marketplace_app`, `DEMO_USERS=100`,
`DEMO_PRODUCTS=1000`, `DEMO_ORDERS=10000`, `RESET_PERFORMANCE_DATA=0`.
Los rangos admitidos son 1..10.000 compradores, 5..100.000 productos y 0..100.000 pedidos.

## Dataset por defecto y credenciales

| Datos | Cantidad y contenido |
|---|---|
| Cuentas | **107**: pool de 100 compradores, cuatro cuentas legibles y tres vendedores adicionales |
| Tiendas | **5**, `Perf demo Tienda 01` .. `05`, con propietarias diferentes y métodos STANDARD/EXPRESS |
| Categorías | 4 categorías activas propias: Moda, Papelería, Hogar, Tecnología, con prefijo `Perf demo` |
| Productos | **1.000 totales**, no 1.004: `Producto demo 0001` .. `0996` más cuatro con nombres humanos |
| Nombres humanos | `Camiseta demo`, `Libreta demo`, `Lámpara demo`, `Producto sin stock` |
| Pedidos | **10.000**, con `transaction_id` único `perf-demo-00000001` .. `perf-demo-00010000` |
| Líneas | **10.000**, una por pedido, cantidad 1..3, producto y tienda coherentes |
| Historial | **15.000** filas: creación CONFIRMED y, cuando corresponde, transición a IN_PREPARATION |
| Dirección | Una dirección ficticia compartida, con snapshot independiente en cada pedido |

Todas las cuentas usan el valor proporcionado en **DEMO_PASSWORD**; no hay contraseña
predeterminada publicada. Solo se almacena BCrypt coste 10, compatible con Spring Security.
La contraseña entra a `htpasswd` por stdin, nunca como argumento ni en el SQL. `DB_PASSWORD`
se transmite al cliente MySQL por entorno y no se imprime.

| Cuenta | Roles / propiedad |
|---|---|
| `buyer001@marketplace.demo` .. `buyer100@marketplace.demo` | COMPRADOR; pool de carga |
| `comprador@marketplace.demo` | COMPRADOR; demo manual |
| `vendedor@marketplace.demo` | VENDEDOR; tienda 01 |
| `multirrol@marketplace.demo` | COMPRADOR + VENDEDOR; tienda 02 |
| `soporte@marketplace.demo` | SOPORTE |
| `seller003@marketplace.demo` .. `seller005@marketplace.demo` | VENDEDOR; tiendas 03..05 |

No se añade ADMIN: no hace falta para usar el dataset. Las cuentas están activas y confirmadas;
los cinco vendedores tienen aceptación de la versión actual de `SellerTerms.VERSION`.
El script no asigna ni cambia la dueña de la tienda principal ni de tiendas preexistentes.

Precios, stock, categorías, tiendas y estados se calculan por índice, sin azar. Los productos
numerados múltiplos de diez quedan PAUSED (`active=false`); los demás ACTIVE. Cada producto
tiene una imagen SVG embebida, sin dependencia de servidores de imágenes. El último tiene
stock cero. Los pedidos se asignan solo a productos activos con stock positivo; el stock
representa el saldo disponible después de las ventas históricas simuladas, no un nuevo descuento.

Se usan únicamente **CONFIRMED** e **IN_PREPARATION** (5.000 de cada uno por defecto), pago
APPROVED y transiciones reales de `OrderStatus`. Fechas UTC deterministas en los 180 días
que terminan el **1 de julio de 2026**; la preparación ocurre una hora después de la creación.
Los totales son `cantidad × precio + envío`: STANDARD 10.000 y EXPRESS 20.000, como el checkout.
No se inventan envíos, entregas, cancelaciones, reclamaciones, devoluciones ni reembolsos.
Son snapshots sintéticos para demostración/analítica, no transacciones de una pasarela real
ni una simulación completa de auditoría, notificaciones o logística. Para esos escenarios,
operar el backend o usar los scripts CU-20/CU-23 en una base independiente.

## Idempotencia, identificación y reset seguro

El script mantiene **una tabla auxiliar** `performance_demo_seed_manifest`, exclusivamente
para este dataset, fuera de Flyway. Guarda las claves primarias exactas y huellas de las filas
creadas, más la configuración de volúmenes. Esto evita adoptar datos ajenos solo por su nombre:
un producto preexistente llamado `Camiseta demo` permanece intacto.

La carga se hace en una transacción con un lock de MySQL. Los valores se generan en lotes de
500 y se envían con unas pocas invocaciones al cliente, no con un `docker exec` por pedido.
Si falla, MySQL revierte la transacción; la tabla auxiliar puede quedar vacía. No se deshabilitan
las FK ni los CHECK. Una segunda ejecución con los mismos volúmenes no duplica filas, no
restaura stock/contraseñas y muestra los recuentos del dataset existente. Un cambio de volúmenes
o de versión de términos requiere reset o una nueva base. Las cuentas, tiendas o transacciones
reservadas que existan sin pertenecer al manifiesto hacen abortar la carga.

Para recrear **un dataset aún intacto** con los mismos u otros volúmenes:

```bash
# Con las variables y contraseñas de la BD desechable ya exportadas:
RESET_PERFORMANCE_DATA=1 ./scripts/performance-demo-seed.sh
# Ejemplo pequeño, incluidos los cuatro productos humanos:
RESET_PERFORMANCE_DATA=1 DEMO_USERS=10 DEMO_PRODUCTS=100 DEMO_ORDERS=200 \
  ./scripts/performance-demo-seed.sh
```

El reset elimina únicamente las claves registradas, de hijas a padres, y regenera el dataset.
Los IDs autoincrementales y la sal BCrypt pueden cambiar; los datos de negocio son deterministas.
**No es un limpiador general:** si una fila fue editada durante la demo o recibió dependencias
ajenas, aborta sin borrar nada. Comprueba también relaciones con CASCADE/SET NULL y referencias
sin FK, incluidas sesiones, reportes y notificaciones. Las referencias polimórficas se comprueban
conservadoramente: una coincidencia de ID puede impedir el reset aunque pertenezca a otro tipo.
Tras usar/modificar el dataset, crear otra BD desechable y volver a aplicar Flyway es la opción
reproducible. No borrar el manifiesto para forzar el reset.

## Validación

```bash
bash -n scripts/performance-demo-seed.sh
# Si está instalado:
shellcheck scripts/performance-demo-seed.sh
```

Validar en un MySQL desechable: ejecución inicial, segunda ejecución sin duplicados, reset,
totales por línea, tienda del producto/pedido y preservación de una fila ajena. El resumen final
cuenta solo filas registradas en el manifiesto, no los datos que ya existían en la base.
No hace falta ni debe ejecutarse este script contra una BD con información importante.
