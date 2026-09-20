# Cobertura de pruebas del backend

## Objetivo y estado validado

El mínimo obligatorio es **95 % de cobertura global de líneas** del módulo
`backend/demo`. Se prioriza probar la lógica crítica y sus resultados, incluidos
los errores y las condiciones límite, sobre alcanzar un porcentaje artificial.

Estado validado al cerrar esta etapa:

- **248 pruebas automatizadas**, sin fallos, errores ni pruebas omitidas.
- **99,68 % de líneas** cubiertas (1564 de 1569).
- **99,28 % de ramas** cubiertas (413 de 416).

Las áreas críticas son autenticación, sesiones, roles, recuperación de contraseña,
checkout, inventario, pedidos, ownership (propiedad de los pedidos) y cancelación.

## Ejecución y reporte

Desde `backend/demo`, con Java 21 y Docker disponible para las pruebas con
Testcontainers y MySQL:

```sh
./mvnw clean verify
```

El reporte HTML se genera en
`backend/demo/target/site/jacoco/index.html`. En el mismo directorio están
`jacoco.xml` y `jacoco.csv`; los resultados de las pruebas están en
`backend/demo/target/surefire-reports/`.

El `pom.xml` configura `jacoco:check` en la fase `verify` con una regla global
(`BUNDLE`) sobre `LINE / COVEREDRATIO`, con mínimo `0.95` y fallo habilitado.
**La verificación falla si la cobertura global de líneas baja de 95 %.**
No hay un umbral de ramas, no se exige 100 % y no se añaden exclusiones de clases.

## Integración continua

`.github/workflows/backend-ci.yml` ejecuta `./mvnw clean verify`; por tanto, el
pipeline falla por debajo de 95 % de líneas. La construcción y publicación de
imágenes dependen de que esa validación termine correctamente.

CI conserva el reporte JaCoCo y los resultados de Surefire como artefacto
`backend-reports-${{ github.sha }}` durante **14 días**. El paso de conservación
usa `always()`, por lo que también intenta guardar los reportes generados cuando
falla la verificación.

## Huecos restantes

Los pocos caminos no cubiertos son defensivos y técnicamente inalcanzables bajo
la JVM y el flujo soportados: ausencia de SHA-256, comprobaciones redundantes
tras `Optional.map`, estados que el flujo de asignación impide y un límite de
espera que no se alcanza antes de agotar los reintentos. También queda una
comprobación defensiva de existencia después de un upsert transaccional que
mantiene bloqueada la fila.

Las clases vacías y sin uso `PaymentRequest` y `PaymentResponse` de
`payments.application.dto` conservan sus constructores sin ejecutar; son distintas
de los DTO utilizados por la API en `payments.infrastructure.web`.

No se agregan pruebas artificiales únicamente para aumentar el porcentaje,
ni se alteran proveedores criptográficos o reglas de negocio para forzar caminos
imposibles. Estos huecos permanecen visibles en JaCoCo.
