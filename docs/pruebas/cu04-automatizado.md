# CU-04: verificación automatizada

Desde la raíz del proyecto, con Docker Desktop iniciado, Java 21, Maven, Node,
dependencias del frontend y Chrome instalados:

```powershell
node scripts/probar-cu04.mjs
```

No es necesario bajar los servicios del Marketplace ni introducir credenciales.
La integración crea MySQL y RabbitMQ aislados mediante Testcontainers. La interfaz
usa los puertos 4434 y 4435, separados del frontend local. Deben estar libres.
La primera ejecución puede descargar imágenes y dependencias.

El comando ejecuta secuencialmente backend, compilación de producción, interfaz
de favoritos y Web Push. Ante un fallo se detiene con código distinto de cero.
Guarda logs de cada etapa y `resultado.json` con código de salida y duración en
`artifacts/cu04/<fecha>/`, excluido de Git. Las etapas posteriores a una fallida
no aparecen como aprobadas. Los reportes detallados del backend quedan además en
`backend/demo/target/surefire-reports`; Playwright conserva trazas de fallos.

### Cobertura de código del backend

La etapa de backend ejecuta `mvn -Pcu04-coverage clean verify`: genera cobertura
desde cero, sin sumar ejecuciones anteriores. JaCoCo exige **más del 95 % de
líneas** (`COVEREDRATIO >= 0.9501`) en cada uno de estos paquetes completos:

- `com.transformersas.marketplace.favorites`.
- `com.transformersas.marketplace.notifications.infrastructure.push`.
- `com.transformersas.marketplace.notifications.infrastructure.queue`.

No se excluyen controladores ni caminos de error de estos paquetes. La regla
también se aplica al `mvn clean verify` general de CI, que conserva además su
umbral global de 95 %. El perfil del CU-04 verifica su alcance específico; no
pretende medir todo el backend ejecutando solo sus pruebas.

Para ver el informe del CU-04, abrir en el navegador:

```text
backend/demo/target/site/jacoco-cu04/index.html
```

El informe general queda en `backend/demo/target/site/jacoco/index.html` cuando se
ejecuta la suite completa. La cobertura de ramas se muestra por separado; la
regla aquí descrita es de líneas Java, no de ramas ni de TypeScript del frontend.
Las pruebas Playwright verifican comportamiento y no producen ese porcentaje.

## Cobertura y alcance

### Demostración visual lenta

Desde `frontend`, ejecutar:

```powershell
npm.cmd run demo:cu04
```

Abre Chrome visible, una prueba a la vez, con 700 ms entre acciones del navegador.
No necesita avanzar manualmente en el inspector. Utiliza las mismas ocho pruebas
de `favoritos.spec.ts`, con API controlada; no modifica la base de datos local.
Usa el puerto 4436 y un límite de 120 segundos por prueba para la presentación.
La verificación normal conserva su velocidad y sus límites originales.


| Requisito o flujo | Evidencia automática |
| --- | --- |
| RF-011 guardar, retirar, persistir y evitar duplicados | Integración con MySQL e interfaz con recarga. |
| RF-012/013 consulta y datos actuales | Integración de favoritos con actualización de precio y estado; interfaz bloquea acciones de publicaciones inactivas. |
| RF-014 enlace al producto | Interfaz abre detalle desde enlace compartido y notificación. Las acciones de carrito y comparación requieren además su regresión propia. |
| RF-133 precio objetivo | Objetivo ya cumplido no deja alerta activa; edición, activación y cumplimiento con notificación. |
| RF-134 disponibilidad | Variante agotada por nombre; reposición y modificación; interfaz impide alertas si no hay variantes agotadas. |
| RF-135 administración | Pausa, reactivación, edición y eliminación. |
| RF-136/138 evaluación segura | Publicaciones inactivas, variantes ausentes, concurrencia, rollback y control de duplicados. |
| RF-137 avisos | Notificación interna, enlace al producto, procesamiento RabbitMQ y envío push controlado. |
| RNF-010 seguridad | Propiedad de favoritos y alertas, CSRF, suscripciones entre cuentas y sesiones vencidas. |
| RNF-014/037/042/045 fallos externos | Broker no disponible y recuperación, respuesta 503, reintentos, cola de fallos, conservación del aviso interno y timeout HTTP. |
| RNF-020 actualización de bandeja | Prueba visual espera el aviso dentro de cinco segundos con API controlada. No certifica latencia de producción. |

Las clases ejecutadas son `FavoritesIntegrationTests`,
`NotificationsIntegrationTests`, `NotificationInboxTests`,
`PushQueueIntegrationTests` y `WebPushTransportTests`. La interfaz se ejecuta con
`frontend/e2e/favoritos.spec.ts` y `frontend/e2e/push-browser.spec.ts`.

Las pruebas de integración usan la lógica real y bases reales, pero la interfaz
utiliza respuestas HTTP controladas: no constituyen un único recorrido de navegador
a backend real. El push prueba cifrado, permiso, suscripción y service worker;
el proveedor externo se controla para lograr resultados repetibles.

## Qué se comprueba manualmente

El banner de Windows y la entrega real por el proveedor requieren permisos y
configuración del navegador y del sistema operativo. No se fuerza el permiso ni
se presenta `SENT` como prueba de que el usuario vio el banner. Seguir la
[guía de Web Push y RabbitMQ](../ejecucion/notificaciones-push-rabbitmq.md).

RNF-018 requiere una medición de rendimiento separada y un entorno identificado.
Existe `FavoritesConcurrencyTests`, activable con `-Dcu04.performance=true`;
no se incluye en esta ejecución funcional ni se certifica rendimiento por defecto.

Las suites ya están incluidas en el workflow de CI: Maven verifica el backend y
Playwright ejecuta favoritos y push. La guía permite repetir localmente el
subconjunto del CU-04 sin ejecutar todos los casos de uso.

## Resultado verificado el 9 de noviembre de 2026

Ejecución completa con `node scripts/probar-cu04.mjs`:

| Etapa | Resultado |
| --- | --- |
| Backend con MySQL y RabbitMQ aislados | 42 pruebas correctas, sin fallos ni omisiones. |
| Compilación de producción | Correcta. |
| Interfaz de favoritos y alertas en Chrome | 8 pruebas correctas. |
| Push con proveedor controlado y service worker real | 4 pruebas correctas. |

Evidencias locales: `artifacts/cu04/2026-11-09T15-08-21-238Z/`.
No se ejecutó en esta verificación la prueba de carga de 100 sesiones ni una
entrega externa real de push. Los resultados corresponden a estas etapas,
no a una certificación completa de todos los requisitos no funcionales.

## Verificación previa al commit: 9 de noviembre de 2026

Se ejecutó la regresión completa del backend y después el perfil del CU-04 con
pruebas adicionales. No se rebajó el umbral global existente ni se excluyeron
clases de los tres paquetes declarados del CU-04.

| Verificación | Resultado |
| --- | --- |
| `mvn clean verify` general | 1.677 pruebas reportadas: 1.676 aprobadas y 1 de rendimiento opcional omitida; cero fallos y errores. |
| `mvn -Pcu04-coverage verify`, con pruebas adicionales | 66 reportadas: 65 aprobadas y la misma prueba opcional omitida; reglas JaCoCo aprobadas. |
| Regresión completa de interfaz en Chrome | 101 pruebas aprobadas. |
| Service worker y push en Chrome | 4 pruebas aprobadas con proveedor controlado. |
| Compilación de producción del frontend | Correcta. |
| Prueba opcional `FavoritesConcurrencyTests`, ejecutada por separado | 1 aprobada: 100 compradores, 900 operaciones y P95 de 903,20 ms (límite 2.000 ms). No quedó omitida en esta ejecución separada. |

| Alcance de cobertura de líneas Java | Cubiertas / total | Porcentaje |
| --- | --- | --- |
| Backend global, suite completa | 8.298 / 8.459 | 98,10 % |
| Favoritos y alertas, perfil CU-04 | 125 / 128 | 97,66 % |
| Web Push, perfil CU-04 | 103 / 105 | 98,10 % |
| Cola RabbitMQ, perfil CU-04 | 53 / 53 | 100 % |

El perfil genera datos propios en `target/jacoco-cu04.exec` con `append=false`.
El HTML del perfil muestra únicamente los tres paquetes declarados, completos;
su porcentaje no es el de todo el backend. La regla se evalúa por paquete, para
que un paquete con mejor cobertura no compense otro inferior al umbral.

Los XML finales de Surefire acumulan 1.690 resultados aprobados, sin fallos,
errores ni omisiones, tras la regresión completa, las pruebas adicionales y la
ejecución separada de concurrencia. Este acumulado toma el último reporte de cada
clase; no representa una única invocación de Maven ni suma las suites repetidas.

Se comprobó también el control de cobertura con los datos ya medidos: el umbral
real `0.9501` pasa y un POM temporal que exige `1.0` falla específicamente para
favoritos y push. El archivo temporal fue retirado. Esto verifica que el filtro
por paquetes de la regla se aplica, en lugar de aceptar una regla sin coincidencias.

Pruebas añadidas: ciclo HTTP completo de alertas, límite de 500 alertas,
publicación que pasa a privada, variantes duplicadas al modificar o reactivar,
recorrido del programador tras un fallo y al completar lotes, permiso de vendedor
para su tienda, entradas push inválidas, interrupción del envío, mensajes de cola
inválidos, recuperación administrativa, límite de cinco dispositivos y consulta
del estado real de suscripción.

Las evidencias locales están en `artifacts/cu04-validacion/2026-11-09/`, incluidos
los XML de JaCoCo, JSON de cobertura, logs y una copia navegable del informe CU-04
en `informe-cu04/index.html`. El comando automático también copia su XML y genera
`cobertura.json` en la carpeta de evidencias de cada ejecución.

Estos porcentajes son de **líneas Java**, no una afirmación de cobertura de ramas
o de TypeScript. La recepción externa real y el banner de Windows siguen siendo
comprobaciones manuales del entorno.

La medición de rendimiento usa MockMvc y MySQL en Testcontainers con sesiones
reales; no incluye navegador, red pública ni proxy. Es evidencia local del equipo
de desarrollo, no una certificación de latencia en producción. Para repetirla
sin compilaciones o navegadores concurrentes, desde `backend/demo`:

```powershell
mvn.cmd '-Dtest=FavoritesConcurrencyTests' '-Dcu04.performance=true' test
```
