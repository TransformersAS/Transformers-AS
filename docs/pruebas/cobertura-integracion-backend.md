# Cobertura exclusiva de integración del backend

## Alcance y separación reproducible

El objetivo solicitado es **cero líneas missed**, sin excluir producción y sin sumar unit tests. El perfil `integration-coverage` de `backend/demo/pom.xml` selecciona únicamente las clases concretas de `src/test/integration-tests.includes`.

La lista se obtiene por `@SpringBootTest`, `@Testcontainers` o herencia transitiva de una clase que tenga esas anotaciones. Esto incluye `AbstractIntegrationTest`, `AbstractTrackingTest`, `ContentReportSupport` y `ReturnsTestSupport`. No se seleccionan clases por contener la palabra “Integration” en su nombre. La lista original tenía 71 clases; la lista final tiene 80 (9 clases nuevas), añadidas por el mismo criterio. La clasificación reproduce la solicitada; algunas pruebas preexistentes de integración utilizan espías para inyectar fallos transaccionales. No se presentan esos fallos inyectados como respuestas de un proveedor externo real.

El auditor `scripts/integration-suite.py` rechaza una lista desactualizada y nombres duplicados. Revisar la lista al añadir pruebas:

```bash
cd backend/demo
python3 scripts/integration-suite.py --write
python3 scripts/integration-suite.py
```

No añadir clases unitarias a esa lista ni anotar una prueba unitaria con Spring solo para incluirla: la prueba debe integrar realmente componentes, persistencia o transporte. Las nuevas pruebas usan beans Spring, HTTP real contra WireMock o MySQL Testcontainers; no reutilizan la ejecución de los tests unitarios existentes.

## Ejecutar y verificar

Requisitos: JDK 21, Docker disponible y Python 3 para el auditor. Maven se obtiene mediante el wrapper. En macOS, si el Java predeterminado es otro:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

Desde la raíz de Transformers-AS, ejecución auditada completa:

```bash
bash backend/demo/scripts/run-integration-coverage.sh
```

Equivalente desde `backend/demo`, sin el paso adicional de auditoría:

```bash
./mvnw -Pintegration-coverage clean verify
```

Este comando ejecuta solo integración, genera `target/site/jacoco/jacoco.xml` y el HTML, y aplica el gate **LINE / MISSEDCOUNT / maximum=0**. Cualquier línea sin cubrir hace fallar `verify`, aunque todas las pruebas pasen. El threshold normal de 0.95 no se redujo ni se cambió; el perfil reemplaza su regla por cero líneas missed.

Para medir durante una iteración sin invocar todavía el gate (no equivale a aprobarlo):

```bash
./mvnw -Pintegration-coverage clean test jacoco:report
python3 scripts/integration-coverage-report.py
```

Para regenerar el reporte y verificar el gate sobre la última ejecución de integración, sin repetir tests:

```bash
./mvnw -Pintegration-coverage jacoco:report jacoco:check@check-line-coverage
```

Los datos de integración se escriben en `target/jacoco-integration.exec`, con `append=false`, y los resultados JUnit en `target/surefire-integration-reports/`. Los datos unitarios/normales usan el archivo JaCoCo habitual y no se leen en este perfil. El comando reproducible comienza con `clean` para evitar reportes antiguos. El HTML está en `target/site/jacoco/index.html`; después de una ejecución normal, regenerarlo con el perfil antes de presentarlo como integración.

No utilizar `-Dtest=...` para certificar la suite completa: sirve únicamente para diagnóstico y puede sustituir la selección de Surefire. No usar `-DskipTests`, `-Dmaven.test.skip` ni exclusiones de JaCoCo para acreditar el objetivo.

El perfil fija Gemini sin clave y con URL loopback por defecto. Las pruebas que necesitan Gemini sobrescriben la URL con un WireMock de puerto dinámico y una clave ficticia `wiremock-only`. No requieren `.env`, una API key real ni peticiones a Google. El único cambio de producción permite configurar `gemini.base-url`, con el valor original `https://generativelanguage.googleapis.com` como predeterminado.

## Integraciones añadidas

Rutas relativas a `backend/demo/src/test/java/com/transformersas/marketplace/`:

| Clase | Integración y comportamiento comprobado |
| --- | --- |
| `recommendation/integration/GeminiHttpIntegrationTests.java` | Spring + HTTP WireMock: URL/modelo, clave, body y esquema; prompt con historial/búsqueda/productos; IDs ordenados y sin duplicados; clave ausente; candidates/content/parts/text ausentes; array inválido/vacío; 400/429/500/503 sin reintentos. |
| `recommendation/integration/RecommendationFlowIntegrationTests.java` | Sesiones/CSRF, controladores, servicios, repositorios, MySQL y WireMock: catálogo vacío, orden por stock, historial persistido, filtrado de IDs, límite de cinco, fallback y validaciones de interacciones sin persistencia parcial. |
| `recommendation/integration/RecommendationWithoutKeyIntegrationTests.java` | Historial en MySQL y respuesta GENERAL_NO_API_KEY sin proveedor externo. |
| `logistics/LogisticsHttpIntegrationTests.java` | Bean HTTP de producción, configuración Spring y circuito real; contratos de envíos/devoluciones/métodos, parseo de eventos/evidencia, errores HTTP, timeout, conexión fallida y circuito abierto. |
| `logistics/TrackingLifecycleIntegrationTests.java` | Scheduler real y MySQL: el hilo programa barridos, persiste progresión logística de envío/retorno, se detiene; un fallo SQL temporal no impide el barrido posterior. |
| `orders/CancellationPersistenceIntegrationTests.java` | Repositorios JDBC: lectura completa, unicidad de cancelación, conservación del motivo y resolución compare-and-set de incidencias. |
| `users/infrastructure/config/LocalProvisioningIntegrationTests.java` | Runners del perfil local y MySQL: crea cuenta/producto/propiedad, respeta cambios posteriores y no finge verificación del correo. |
| `stores/StoreBoundaryIntegrationTests.java` | HTTP/CSRF/MySQL: texto inválido sin escrituras, límites de dimensiones de imagen e identidad de imágenes persistidas sin exponer binarios. |
| `stores/infrastructure/config/OwnerRunnerIntegrationTests.java` | Runner y servicios/repositorios reales: cuenta inexistente/comprador/vendedor, tienda ya poseída, asignación idempotente, tienda ausente y recuperación ante fallo SQL. |

## Bloqueo técnico para 100% sin alterar el alcance

Existe al menos una línea demostrablemente inalcanzable mediante los caminos públicos de la aplicación actual:

`orders/application/usecase/ApplyLogisticsUpdateUseCase.java`, método privado `notifyParties`, `default -> throw new IllegalStateException("Estado sin aviso logístico: " + target)`.

La única llamada a ese método procede de `apply`. El tipo de entrada es el enum `ShipmentEventType`: `NEXT_ATTEMPT_SCHEDULED` retorna antes del mapeo; los otros seis tipos se convierten a seis `OrderStatus` y están todos enumerados explícitamente en el switch. Además, se comprueba `allowedLogisticsTargets()` antes de llamar al método. Ninguna petición HTTP, evento válido ni fila MySQL puede introducir un séptimo tipo que alcance ese `default`. Ejecutarlo por reflexión contra un método privado sería una prueba unitaria de esa defensa, no una integración real; borrar/excluir esa línea incumpliría las reglas solicitadas.

También quedan defensas cuya entrada inválida es impedida por las capas anteriores: relaciones NOT NULL/FK y CHECK de cantidades/stock en reservas y carrito. No se desactivan restricciones para fabricar un esquema diferente al real. El catch de `NoSuchAlgorithmException` para `MessageDigest.getInstance("SHA-256")` en `shared/files/ImageValidator.java` requiere alterar los proveedores criptográficos de la JVM, no una entrada HTTP de imagen normal.

**No se afirma que todas las líneas pendientes sean imposibles.** El inventario final identifica todas; algunas son escenarios adicionales de integración todavía no implementados. El bloqueo probado anterior ya impide cumplir literalmente 100% conservando el código y las restricciones. El gate permanece estricto y debe seguir rojo mientras exista cualquier línea missed.

## Resultado medido

**Ejecución final limpia:** `bash backend/demo/scripts/run-integration-coverage.sh` (Java 21), 80 clases, **864 tests**, 0 fallos, 0 errores, 0 omitidos. La lista de clases reportadas coincide exactamente con la lista de integración.

| LINE covered | LINE missed | LINE total | Porcentaje |
| ---: | ---: | ---: | ---: |
| 6811 | 195 | 7006 | **97.216671%** |

El objetivo de 100% **no se alcanzó**. La fase de pruebas terminó bien y el JAR se construyó; `verify` terminó con código 1 exclusivamente por el gate, que informó: `lines missed count is 195, but expected maximum is 0`. No se rebajó para convertir este resultado en verde.

La ejecución normal completa de `./mvnw clean verify` no se volvió a ejecutar en este cambio. Se mantuvo su configuración y se ejecutó la regresión unitaria original de Gemini por separado (10 casos aprobados), sin contar esa cobertura en el porcentaje anterior.


La primera iteración completa ejecutó 858 tests en 78 clases, con 0 fallos, 0 errores y 0 omitidos: 6790 líneas cubiertas / 216 missed / 7006 totales = 96.916928%. Toda la familia `recommendation` quedó sin líneas missed. El total aumentó de 7005 a 7006 por la inicialización de la URL configurable: no se eliminó lógica.

Después se añadieron 6 casos de tiendas en 2 clases. Su ejecución focalizada pasó junto con los 10 casos unitarios originales de Gemini, para comprobar compatibilidad del constructor; estos 10 casos **no se incorporan** al reporte de integración. La ejecución limpia final vuelve a seleccionar las 80 clases y borra los datos normales de esa comprobación.

El inventario reproducible sale de `python3 scripts/integration-coverage-report.py`. El script comprueba además que los nombres de clase reportados pertenecen a la lista permitida y muestra si está completa. Los resultados normales, que contienen unit tests, no se usan como porcentaje de integración.


## Archivos modificados y creados

Modificados:

- `backend/demo/pom.xml`: perfil aislado, archivo de datos propio y gate LINE con cero missed. El flujo normal conserva su regla del 95%.
- `backend/demo/src/main/java/com/transformersas/marketplace/recommendation/infrastructure/GeminiRecommendationClient.java`: propiedad `gemini.base-url`; conserva el constructor y el endpoint predeterminado de producción. No cambia el parseo ni la política de errores.

Creados:

- Las nueve clases de integración enumeradas arriba (46 casos JUnit adicionales).
- `backend/demo/src/test/integration-tests.includes`: lista explícita reproducible, sin clases unitarias.
- `backend/demo/scripts/integration-suite.py`: descubre/verifica la lista mediante anotaciones y herencia.
- `backend/demo/scripts/run-integration-coverage.sh`: audita y ejecuta `clean verify` con el perfil estricto.
- `backend/demo/scripts/integration-coverage-report.py`: calcula totales, verifica nombres de clases reportadas y enumera todas las líneas missed.
- `docs/pruebas/cobertura-integracion-backend.md`: comandos, alcance, pruebas y límites del resultado.

No se añadieron dependencias, exclusiones de cobertura ni cambios funcionales para eliminar defensas. No se modificaron los tests unitarios existentes. No se hizo commit.


## Inventario completo de las 195 líneas pendientes

Extraído del XML final de integración, sin redondear ni excluir clases. Todas las rutas de la tabla son relativas a `backend/demo/src/main/java/com/transformersas/marketplace/`.

La línea 113 de `ApplyLogisticsUpdateUseCase` es el bloqueo lógico probado arriba. Las defensas de imágenes y las restricciones de esquema explican otros límites de acceso. Para las demás filas, este inventario indica **cobertura pendiente**, no una afirmación de imposibilidad: quedan escenarios de concurrencia, validación, configuración, reintentos y manejo de fallos que no se ampliaron después de identificar el bloqueo del objetivo literal.

| Archivo | Líneas missed |
| --- | --- |
| `auth/application/usecase/ManageAccountSessions.java` | 75, 76 |
| `auth/application/usecase/RecoverAccountPassword.java` | 88 |
| `auth/application/usecase/VerifyAccountEmail.java` | 52, 63, 108 |
| `auth/infrastructure/security/BuyerAccess.java` | 13, 16, 23 |
| `auth/infrastructure/security/LoginSessionPolicy.java` | 17 |
| `auth/infrastructure/security/SessionSellerActorProvider.java` | 48, 50, 51, 62 |
| `checkout/CheckoutService.java` | 154, 177, 180, 207, 210 |
| `logistics/application/dto/TrackingPolicy.java` | 14 |
| `logistics/application/usecase/PollActiveTrackingUseCase.java` | 57, 58, 65, 66 |
| `logistics/application/usecase/ProcessReturnUpdateUseCase.java` | 81, 82, 83, 84, 85, 121 |
| `logistics/application/usecase/ProcessShipmentUpdateUseCase.java` | 74, 75, 76, 77, 78 |
| `logistics/domain/model/ReturnRequestData.java` | 24 |
| `logistics/domain/model/ReturnTrackingUpdate.java` | 24, 27 |
| `logistics/domain/model/ReturnTransitions.java` | 49, 53 |
| `logistics/domain/model/ShipmentRequest.java` | 26 |
| `logistics/domain/model/TrackingConflictException.java` | 10, 11 |
| `logistics/domain/model/TrackingUpdate.java` | 31 |
| `logistics/infrastructure/gateway/ConfiguredShippingMethodCatalog.java` | 27, 31 |
| `logistics/infrastructure/web/controller/ReturnTrackingController.java` | 66 |
| `logistics/infrastructure/web/controller/WebhookSignatureVerifier.java` | 36, 63, 64 |
| `notifications/application/usecase/DispatchExternalNotificationUseCase.java` | 48, 49, 50 |
| `notifications/infrastructure/dispatch/AfterCommitNotificationDispatcher.java` | 56, 58, 61, 62 |
| `notifications/infrastructure/gateway/HttpExternalNotificationGateway.java` | 75, 76 |
| `orders/application/dto/CancelOrderCommand.java` | 22, 28, 36 |
| `orders/application/usecase/ApplyLogisticsUpdateUseCase.java` | 45, 67, 113 |
| `orders/application/usecase/CancelOrderUseCase.java` | 123 |
| `orders/application/usecase/CreateOrderUseCase.java` | 143 |
| `orders/application/usecase/FindOwnOrders.java` | 28 |
| `orders/application/usecase/RequestOrderCancellation.java` | 29, 32 |
| `orders/domain/model/DeliverySnapshot.java` | 17 |
| `orders/domain/model/Order.java` | 24, 27 |
| `orders/domain/model/OrderSearchCriteria.java` | 25 |
| `orders/infrastructure/persistence/repository/OrderRepositoryAdapter.java` | 110 |
| `orders/infrastructure/web/controller/OrderTrackingController.java` | 65 |
| `payments/application/dto/PaymentRequest.java` | 3 |
| `payments/application/dto/PaymentResponse.java` | 3 |
| `payments/infrastructure/gateway/MockPaymentGateway.java` | 25, 70, 77 |
| `payments/infrastructure/persistence/repository/JdbcRefundRepository.java` | 59 |
| `product/ProductContentOwnerResolver.java` | 32 |
| `product/ProductContentSnapshotProvider.java` | 39, 40 |
| `product/ProductIds.java` | 16, 17 |
| `reports/application/ContentModerationStateService.java` | 58 |
| `reports/application/ModerationScheduler.java` | 33, 35, 36, 44, 45 |
| `reports/application/ReportSubmissionService.java` | 103, 105, 106, 108, 120, 137, 152 |
| `reports/application/ReporterAccess.java` | 23 |
| `reports/application/ReporterReportQueryService.java` | 127 |
| `reports/domain/model/ReportReason.java` | 40 |
| `reports/domain/model/ReporterOutcome.java` | 16 |
| `reports/infrastructure/notification/LoggingExternalNotificationClient.java` | 19, 21 |
| `reports/infrastructure/persistence/entity/ContentModerationStateId.java` | 23, 24, 29 |
| `reports/infrastructure/persistence/entity/ModerationCaseEntity.java` | 82, 96 |
| `reports/infrastructure/web/controller/ReportController.java` | 117, 118 |
| `reservation/InventoryReservation.java` | 42 |
| `reservation/InventoryReservationService.java` | 113, 138, 141, 150, 153, 307, 325, 363, 368, 373, 376, 388, 391, 505 |
| `returns/application/ReturnBuyerAccess.java` | 20 |
| `returns/application/dto/ReturnViews.java` | 33 |
| `returns/application/usecase/RequestReturnUseCase.java` | 98, 116 |
| `returns/application/usecase/ReturnMethodSelectionUseCase.java` | 97, 99, 127 |
| `returns/application/usecase/ReturnQueryService.java` | 87 |
| `returns/application/usecase/ReturnSweepUseCase.java` | 85, 87, 120, 136, 177, 178 |
| `returns/domain/eligibility/ReturnEligibility.java` | 32 |
| `returns/domain/model/ReturnLine.java` | 10 |
| `returns/domain/model/ReturnPolicy.java` | 26, 30 |
| `returns/domain/model/ReturnReason.java` | 29 |
| `returns/domain/model/ReturnRequest.java` | 116, 142, 159, 195, 234, 279, 296, 298, 300, 317, 320, 349, 353, 470, 473 |
| `returns/domain/port/OrderForReturn.java` | 34, 35 |
| `returns/infrastructure/config/ReturnSweepScheduler.java` | 18, 19, 20, 24, 25 |
| `returns/infrastructure/web/controller/BuyerReturnController.java` | 138, 139 |
| `sellers/SellerRegistrationService.java` | 121 |
| `shared/ApiExceptionHandler.java` | 40, 46 |
| `shared/files/ImageValidator.java` | 75, 87, 119, 120 |
| `shared/files/UploadSizeExceptionHandler.java` | 21, 22, 23 |
| `shared/http/ExternalRestClients.java` | 24, 32, 35 |
| `shared/web/CorrelationContext.java` | 45 |
| `shared/web/StrictJsonConfiguration.java` | 24 |
| `stock/ExcelSheet.java` | 50, 51 |
| `stores/application/usecase/StatusBasedModificationPolicy.java` | 28 |
| `stores/domain/model/Store.java` | 27, 31, 34 |
| `stores/infrastructure/config/StorePolicyProperties.java` | 17 |
| `stores/infrastructure/persistence/repository/JdbcStoreRepository.java` | 100 |
| `users/domain/model/UserAccount.java` | 13 |
