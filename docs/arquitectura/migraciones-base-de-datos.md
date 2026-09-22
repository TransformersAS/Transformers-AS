# Migraciones del esquema

> Nota de contexto: este documento describe la etapa inicial del proyecto. Las
> afirmaciones sobre la ausencia de migraciones son históricas; consultar los SQL
> actuales en `backend/demo/src/main/resources/db/migration/`.

Flyway ejecuta automáticamente las migraciones de `backend/demo/src/main/resources/db/migration/` al iniciar
Spring Boot, usando el mismo datasource configurado mediante las variables DB_*.
Hibernate únicamente valida el esquema (`ddl-auto=validate`); la inicialización
mediante `schema.sql` y `data.sql` está deshabilitada.

No se crea V1 todavía: la baseline no tiene entidades compartidas ni un esquema
funcional definido. Una V1 vacía consumiría una versión sin representar un cambio.
Cuando se defina el primer esquema compartido, añadir `V1__initial_schema.sql`
con el DDL acordado. No introducir aquí tablas de productos o carrito de otra rama.

Mientras no existan migraciones SQL, Flyway se inicia y mantiene su tabla técnica
`flyway_schema_history`, pero no hay ninguna migración versionada aplicada ni
tablas de negocio. Este README no es una migración.

Las siguientes evoluciones deben añadirse como nuevas migraciones versionadas;
no modificar migraciones ya aplicadas. Docker sigue aprovisionando la base vacía
y las credenciales; Flyway administra las tablas y sus cambios.
