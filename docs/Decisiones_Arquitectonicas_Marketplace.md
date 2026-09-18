# Decisiones Arquitectónicas Definitivas — Marketplace

*Estudio comparativo, justificación y plan de validación*

Proyecto de Arquitectura de Software — primera entrega

Fecha de referencia: 17 de septiembre de 2026

**Alcance:** La aplicación oficial de esta entrega es web. Ionic se usa como conjunto de herramientas para el frontend y deja abierta la posibilidad de empaquetar la misma aplicación para móvil, pero la versión móvil no es un requisito oficial.

> Equipo Transformers
>
> Arquitectura de Software
>
> Jaime Andrés Pavlich-Mariscal
>
> PONTIFICIA UNIVERSIDAD JAVERIANA FACULTAD DE INGENIERÍA
>
> **INGENIERÍA DE SISTEMAS**
>
> **BOGOTÁ, D.C.**

**2026**

# 1. Cómo leer este documento

Este documento no solo enumera tecnologías. Cada decisión explica qué problema resuelve, qué alternativa real se estudió, por qué se eligió una opción y no la otra, qué desventaja o costo aceptamos a cambio del beneficio obtenido y qué prueba concreta debemos ejecutar para demostrar que la decisión funciona.

- Qué necesidad del Marketplace resuelve.
- Qué tecnología se seleccionó.
- Qué alternativa concreta se estudió.
- Por qué sí se seleccionó la tecnología elegida.
- Por qué no se seleccionó la alternativa.
- Qué desventaja o costo aceptamos a cambio del beneficio obtenido.
- Cómo se validará en la práctica.
- Qué evidencia se guardará para la sustentación.
- Qué fuentes oficiales respaldan la decisión.

# 2. Contexto y alcance

El sistema será un Marketplace web con múltiples roles, operaciones que deben mantener los datos consistentes e integraciones con servicios externos. El backend será un monolito modular: existirá un único backend desplegable, pero su código estará separado internamente por áreas del negocio y responsabilidades.

La aplicación móvil no es un requisito oficial. Se utilizará Angular + Ionic porque la solución principal sigue siendo web y esa combinación deja preparado el mismo frontend para un empaquetado móvil futuro mediante Capacitor.

- Casos de uso completos de extremo a extremo.
- Pruebas de integración automatizadas sobre el backend.
- Pruebas funcionales automatizadas.
- Cobertura requerida sobre el backend implementado.
- Despliegue del sistema en mínimo dos computadores.
- Inicio del sistema completo desde un único script.
- Pipeline CI/CD funcional.
- Prueba cuantitativa de Availability.
- Prueba cuantitativa de Performance.
- Ejecución automática de las pruebas incluidas en el pipeline.

# 3. Escala esperada del Marketplace

Las decisiones están pensadas para la escala definida actualmente, no para una plataforma de millones de usuarios. Por eso podemos mantener una arquitectura relativamente simple y medir su comportamiento antes de agregar infraestructura que todavía no necesitamos.

- Aproximadamente 100 usuarios concurrentes.
- Alrededor de 1.000 productos en catálogo.
- Importaciones Excel de hasta 500 filas.
- Análisis sobre aproximadamente 10.000 pedidos simulados.
- Catálogo y detalle: objetivo de respuesta de hasta 3 s.
- Carrito, favoritos, perfil y direcciones: objetivo de hasta 2 s.
- Procesamiento interno de checkout: objetivo de hasta 3 s.
- Mensajes y notificaciones internas: 95 % en hasta 5 s.
- Recomendaciones: 95 % en hasta 5 s.
- Prueba de carga hasta 100 usuarios concurrentes con tasa de error menor al 2 %.

# 4. Tecnologías seleccionadas (stack tecnológico)

| **Área**                            | **Tecnología seleccionada**      | **Alternativa estudiada**   |
|-------------------------------------|----------------------------------|-----------------------------|
| Framework del frontend              | Angular                          | React                       |
| Herramientas web/multiplataforma    | Ionic                            | Flutter                     |
| Backend                             | Spring Boot                      | NestJS                      |
| Lenguaje backend                    | Java 21                          | Java 17                     |
| Construcción del backend            | Maven                            | Gradle                      |
| Arquitectura backend                | Monolito modular                 | Microservicios              |
| Persistencia                        | Spring Data JPA                  | Spring JDBC / JdbcTemplate  |
| Base de datos                       | MySQL 8.4 LTS                    | PostgreSQL                  |
| Herramienta gráfica de BD           | MySQL Workbench (opcional)       | Administración solo por CLI |
| Seguridad                           | Spring Security                  | Keycloak                    |
| Migraciones                         | Flyway Community                 | Liquibase Community         |
| Contenedores                        | Docker                           | Podman                      |
| Orquestación en varios computadores | Docker Swarm                     | Kubernetes                  |
| CI/CD                               | GitHub Actions                   | Jenkins                     |
| Registro de imágenes                | GitHub Container Registry (GHCR) | Docker Hub                  |
| Framework pruebas backend           | JUnit 5 + Spring Boot Test       | TestNG                      |
| BD en pruebas                       | Testcontainers MySQL             | H2                          |
| Simulación APIs externas            | WireMock                         | MockServer                  |
| Cobertura                           | JaCoCo                           | Cobertura                   |
| Pruebas E2E                         | Playwright                       | Cypress                     |
| Pruebas de carga                    | k6                               | Apache JMeter               |
| Comprobaciones de estado            | Spring Boot Actuator             | Endpoint /health manual     |
| Resiliencia                         | Resilience4j                     | Spring Retry                |
| Caché                               | Spring Cache + Caffeine          | Redis                       |
| Control de versiones/plataforma     | Git + GitHub                     | Git + GitLab                |

**Nota:** MySQL Workbench no es el motor de base de datos. El motor es MySQL Server. Workbench se considera solamente una herramienta gráfica local y opcional.

# 5. Backend — Spring Boot

## Necesidad

Necesitamos un backend en Java que exponga APIs, gestione las reglas de negocio, la seguridad, el acceso a datos, las comprobaciones de estado, las integraciones externas y las pruebas automatizadas, manteniendo un único paquete de aplicación que pueda ejecutarse en varias réplicas.

## Tecnología seleccionada

Spring Boot 4.1.1, manteniendo el backend que ya existe en el repositorio.

## Alternativa estudiada

NestJS sobre Node.js/TypeScript.

## ¿Por qué sí seleccionamos esta opción?

Spring Boot ya está inicializado (puesto que se tomó como base un repositorio de la clase de Desarrollo web) y se integra directamente con Spring Security, Spring Data JPA, Actuator, Spring Boot Test, Flyway y MySQL. Mantenerlo evita rehacer el backend y permite concentrar el tiempo en los casos de uso y en los requisitos arquitectonicamente significaticos (ASR).

## ¿Por qué no seleccionamos la alternativa?

NestJS puede implementar el Marketplace, pero no cubre ningún requisito que Spring Boot no pueda resolver en este proyecto. Cambiar obligaría a abandonar el backend existente, usar otro lenguaje en el servidor y rehacer persistencia, seguridad, pruebas y automatización CI/CD sin obtener una mejora medible en los requisitos de calidad.

## Desventaja o costo aceptado (trade-off)

Spring Boot puede consumir más memoria y tardar más en iniciar que tecnologías de backend más livianas. Para una escala de aproximadamente 100 usuarios concurrentes, ese costo es aceptable por la facilidad de integración y de realización de pruebas que ofrece.

## ¿Cómo lo vamos a validar?

> **1.** Ejecutar ./mvnw verify.
>
> **2.** Generar el JAR.
>
> **3.** Construir una imagen Docker del backend.
>
> **4.** Ejecutar dos réplicas idénticas en Swarm.
>
> **5.** Comprobar que ambas responden a la misma API.

## Evidencia que debemos guardar

- Log de ./mvnw verify.
- JAR generado.
- Imagen Docker.
- Salida de Swarm mostrando las réplicas.

### Fuentes oficiales

- https://docs.spring.io/spring-boot/
- https://docs.spring.io/spring-boot/reference/testing/
- https://docs.nestjs.com/

# 6. Lenguaje backend — Java 21

## Necesidad

Necesitamos una versión estable de Java que pueda utilizarse de la misma forma en todos los ambientes y que sea compatible con Spring Boot, Docker y la integración continua (CI).

## Tecnología seleccionada

Java 21.

## Alternativa estudiada

Java 17.

## ¿Por qué sí seleccionamos esta opción?

El repositorio ya está configurado para Java 21 y Spring Boot 4.1.1 lo soporta. Java 21 es una versión con soporte de largo plazo (LTS), por lo que mantenerla evita modificar una configuración que ya funciona.

## ¿Por qué no seleccionamos la alternativa?

Java 17 también sería válido, pero bajar de versión no mejora ningún requisito de calidad y puede introducir diferencias innecesarias entre lo que ya está creado y el entorno final.

## Desventaja o costo aceptado (trade-off)

Los equipos que solo tengan Java 17 no podrán compilar localmente el proyecto en Java 21 sin instalar esa versión. Docker y GitHub Actions definirán el mismo entorno de ejecución para que el proceso sea reproducible.

## ¿Cómo lo vamos a validar?

> **1.** Ejecutar java -version.
>
> **2.** Configurar Java 21 en CI.
>
> **3.** Ejecutar ./mvnw verify local y en CI.
>
> **4.** Construir el backend con una imagen base Java 21.

## Evidencia que debemos guardar

- Salida de java -version.
- Log del setup de Java en Actions.
- Build Docker exitoso.

### Fuentes oficiales

- https://docs.spring.io/spring-boot/system-requirements.html
- https://www.oracle.com/java/technologies/java-se-support-roadmap.html

# 7. Construcción del backend — Maven

## Necesidad

Necesitamos gestionar las dependencias, compilar el código, ejecutar pruebas, validar la cobertura y generar el paquete final mediante un comando que produzca el mismo resultado en diferentes ambientes.

## Tecnología seleccionada

Maven + Maven Wrapper.

## Alternativa estudiada

Gradle + Gradle Wrapper.

## ¿Por qué sí seleccionamos esta opción?

El repositorio ya contiene pom.xml y Maven Wrapper. El ciclo de construcción de Maven incluye etapas para pruebas normales, pruebas de integración y verificación final (test, integration-test y verify); además, JaCoCo y Spring Boot se integran directamente con Maven.

## ¿Por qué no seleccionamos la alternativa?

Gradle también es válido, pero migrar obligaría a sustituir pom.xml y reconfigurar dependencias, complementos, pruebas, cobertura y comandos de CI sin aportar un beneficio necesario para esta entrega.

## Desventaja o costo aceptado (trade-off)

La configuración XML de Maven suele ser más extensa que la de Gradle. Se acepta porque el proyecto ya está preparado y el proceso de compilación y empaquetado será automatizado.

## ¿Cómo lo vamos a validar?

> **1.** Clonar en un ambiente limpio.
>
> **2.** Ejecutar ./mvnw verify sin instalar Maven global.
>
> **3.** Ejecutar el mismo comando en GitHub Actions.
>
> **4.** Comparar resultados.

## Evidencia que debemos guardar

- Log local.
- Log CI.
- JAR de target/.

### Fuentes oficiales

- https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle
- https://maven.apache.org/tools/mavenwrapper.html
- https://docs.gradle.org/current/userguide/gradle_wrapper.html

# 8. Arquitectura backend — Monolito modular

## Necesidad

Necesitamos separar las áreas del negocio y sus responsabilidades sin asumir la complejidad de desplegar y operar cada módulo como un servicio independiente.

## Tecnología seleccionada

Un único backend desplegable organizado por módulos de negocio como auth, users, catalog, inventory, orders, payments, logistics, reviews, returns, notifications y shared.

## Alternativa estudiada

Microservicios.

## ¿Por qué sí seleccionamos esta opción?

Es consistente con el requisito no funcional (RNF) de modularidad y con la escala objetivo: ~100 usuarios concurrentes, ~1.000 productos, importaciones de 500 filas y análisis sobre ~10.000 pedidos. Permite transacciones internas simples, un solo proceso de construcción, un solo flujo de CI/CD y pruebas de integración menos complejas.

## ¿Por qué no seleccionamos la alternativa?

Los microservicios permitirían escalar módulos por separado, pero agregarían comunicación por red, varios despliegues, monitoreo distribuido, acuerdos de comunicación entre servicios y problemas de consistencia que no se justifican para la escala actual.

## Desventaja o costo aceptado (trade-off)

No podremos aumentar la capacidad únicamente de catálogo o pedidos. Si necesitamos más capacidad, tendremos que replicar el backend completo. Para la escala esperada, esa limitación es aceptable.

## ¿Cómo lo vamos a validar? (la vdd que esta me la hizo chat xque yo no sabia como probar eso)

> **1.** Organizar código por dominios.
>
> **2.** Revisar que no existan dependencias circulares entre módulos.
>
> **3.** Compilar todo en un único artefacto.
>
> **4.** Construir una sola imagen Docker.
>
> **5.** Ejecutar 2 réplicas de esa misma imagen.

## Evidencia que debemos guardar

- Árbol de paquetes.
- Un único JAR/imagen.
- docker service ps mostrando 2 réplicas.
- Prueba de tráfico al servicio replicado.

### Fuentes oficiales

- https://docs.spring.io/spring-modulith/reference/

# 9. Persistencia — Spring Data JPA

## Necesidad

El Marketplace tiene numerosas entidades y relaciones. Necesitamos reducir el código repetitivo para guardar y consultar datos sin renunciar a transacciones ni a consultas específicas cuando sean necesarias.

## Tecnología seleccionada

Spring Data JPA usando JPA/Hibernate como capa de mapeo entre objetos Java y tablas de la base de datos (ORM).

## Alternativa estudiada

Spring JDBC / JdbcTemplate como estrategia principal.

## ¿Por qué sí seleccionamos esta opción?

Spring Data JPA reduce el código necesario para los repositorios y facilita relaciones, paginación, transacciones y control de concurrencia mediante bloqueos; además, ya está incluido en el repositorio.

## ¿Por qué no seleccionamos la alternativa?

JdbcTemplate ofrece control directo sobre SQL, pero obligaría a escribir y mantener más consultas y conversiones manuales para un dominio con muchas operaciones de crear, consultar, actualizar y eliminar datos (CRUD). Se podrá usar SQL específico si una consulta crítica lo necesita.

## Desventaja o costo aceptado (trade-off)

Un ORM puede generar consultas poco eficientes si las relaciones se modelan mal. Por eso debemos seguir entendiendo SQL, revisar las rutas críticas de la API y medir el rendimiento antes de optimizar.

## ¿Qué es un ORM?

ORM significa mapeo objeto-relacional (Object-Relational Mapping). Su función es traducir entre objetos Java —por ejemplo, un Producto con id, nombre y precio— y filas o tablas de MySQL. Spring Data JPA usa JPA y normalmente Hibernate para realizar ese mapeo.

## ¿Cómo lo vamos a validar?

> **1.** Levantar MySQL real con Testcontainers.
>
> **2.** Crear datos mediante Repository.
>
> **3.** Consultar los mismos datos.
>
> **4.** Verificar relaciones y constraints.
>
> **5.** Ejecutar endpoints críticos con k6.
>
> **6.** Si un ASR falla, revisar SQL/índices y repetir la medición.

## Evidencia que debemos guardar

- Pruebas de integración.
- Logs de Testcontainers.
- Resultados k6.
- Justificación de índices añadidos.

### Fuentes oficiales

- https://docs.spring.io/spring-data/jpa/reference/
- https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html

# 10. Base de datos — MySQL 8.4 LTS

## Necesidad

El dominio tiene muchas relaciones entre datos y necesita claves foráneas, reglas de integridad, transacciones, índices y consultas que agrupen o calculen información.

## Tecnología seleccionada

MySQL Server 8.4 LTS, donde LTS significa soporte de largo plazo. Como referencia actual, la documentación oficial publicada en septiembre de 2026 corresponde a MySQL 8.4.11 LTS. La implementación deberá fijar una versión concreta de la rama 8.4 para que Docker y Testcontainers utilicen exactamente la misma versión.

## Alternativa estudiada

PostgreSQL, que fue la decisión inicial del proyecto.

## ¿Por qué sí seleccionamos esta opción?

MySQL cubre las capacidades relacionales requeridas y el equipo tuvo problemas configurando PostgreSQL. Estandarizar una sola base de datos evita mantener archivos SQL y configuraciones diferentes. La rama 8.4 tiene soporte de largo plazo (LTS) y Oracle recomienda migrar desde 8.0, que pasó a una etapa de soporte de mantenimiento más limitada (Sustaining Support) en abril de 2026.

## ¿Por qué no seleccionamos la alternativa?

PostgreSQL también podría implementar correctamente el sistema y no se descarta por una limitación técnica. No se selecciona porque el equipo ya comenzó el esquema en MySQL y ningún requisito de calidad depende de una función exclusiva de PostgreSQL; mantener ambos motores solo aumentaría el esfuerzo de integración.

## Desventaja o costo aceptado (trade-off)

MySQL y PostgreSQL no son intercambiables al 100 %. Existen diferencias en tipos de datos, funciones y sintaxis. A partir de esta decisión todo el equipo desarrollará y probará con MySQL; no se mantendrán dos versiones paralelas de los scripts de base de datos.

## MySQL Workbench

MySQL Workbench es una herramienta gráfica opcional para visualizar tablas y ejecutar consultas durante el desarrollo. No es el motor de base de datos ni será necesaria para ejecutar la aplicación, Docker, CI/CD o las pruebas. Si Workbench presenta limitaciones con MySQL Server 8.4, el equipo podrá usar la línea de comandos (CLI) o MySQL Shell sin cambiar la arquitectura.

## ¿Cómo lo vamos a validar?

> **1.** Crear MySQL limpio en Docker.
>
> **2.** Aplicar migraciones Flyway.
>
> **3.** Verificar tablas, claves foráneas y constraints.
>
> **4.** Ejecutar pruebas con Testcontainers MySQL.
>
> **5.** Probar transacciones críticas.
>
> **6.** Ejecutar k6 y revisar índices si una medición incumple el ASR.

## Evidencia que debemos guardar

- Esquema creado desde cero.
- Historial Flyway.
- Tests contra MySQL.
- Resultados de Performance.
- Scripts SQL versionados.

### Fuentes oficiales

- https://dev.mysql.com/doc/refman/8.4/en/
- https://www.mysql.com/support/eol-notice.html
- https://www.postgresql.org/docs/

# 11. Seguridad — Spring Security

## Necesidad

Necesitamos autenticación y autorización para comprador, vendedor, administrador y agente de soporte; una cuenta puede tener varios roles y un rol activo.

## Tecnología seleccionada

Spring Security integrado en el backend.

## Alternativa estudiada

Keycloak como servidor de identidad externo.

## ¿Por qué sí seleccionamos esta opción?

Spring Security se integra directamente con Spring Boot y permite proteger rutas y métodos, definir roles y permisos (authorities) y usar nuestro propio modelo de cuentas en MySQL sin desplegar un sistema adicional.

## ¿Por qué no seleccionamos la alternativa?

Keycloak es potente para inicio de sesión único entre varias aplicaciones (SSO), pero agregaría un servidor crítico adicional que tendríamos que instalar, mantener, asegurar y desplegar. Nuestro Marketplace es una sola aplicación y no requiere compartir el inicio de sesión entre varios sistemas.

## Desventaja o costo aceptado (trade-off)

El equipo será responsable de implementar correctamente el inicio de sesión, el almacenamiento seguro de contraseñas mediante hash, el manejo de sesiones o tokens, el cambio de rol activo, la revocación de acceso y la recuperación de cuenta.

## ¿Cómo lo vamos a validar?

> **1.** Probar endpoint público sin auth.
>
> **2.** Probar endpoint protegido sin auth.
>
> **3.** Probar permiso correcto por rol.
>
> **4.** Probar rechazo por rol.
>
> **5.** Probar cuenta con varios roles y cambio de rol activo.
>
> **6.** Probar credencial/token inválido.

## Evidencia que debemos guardar

- Suite de integración de seguridad.
- Reporte JaCoCo.
- Pipeline pasando los escenarios.

### Fuentes oficiales

- https://docs.spring.io/spring-security/reference/
- https://www.keycloak.org/documentation

# 12. Migracioneas — Flyway Community

## Necesidad

El esquema de la base de datos debe poder crearse y evolucionar automáticamente sin que cada integrante tenga que aplicar cambios manualmente desde Workbench.

## Tecnología seleccionada

Flyway Community con migraciones versionadas SQL compatibles con MySQL.

## Alternativa estudiada

Liquibase Community.

## ¿Por qué sí seleccionamos esta opción?

Flyway encaja bien con una única base de datos relacional y con cambios definidos directamente en SQL. Guarda un historial de las migraciones y Spring Boot puede ejecutarlas al iniciar, por lo que el equipo puede ver con claridad cada cambio realizado sobre la base de datos.

## ¿Por qué no seleccionamos la alternativa?

Liquibase también es válido y permite definir los cambios de base de datos en varios formatos. No necesitamos esa flexibilidad para esta entrega; Flyway introduce menos conceptos y mantiene los cambios visibles directamente en SQL.

## Desventaja o costo aceptado (trade-off)

Usaremos una estrategia de avance mediante nuevas migraciones (roll-forward): una migración que ya fue aplicada no se modifica de forma arbitraria. Si hay que corregir algo, se crea una migración nueva.

## ¿Cómo lo vamos a validar?

> **1.** Crear MySQL vacío.
>
> **2.** Iniciar backend y comprobar que se aplican todas las migraciones.
>
> **3.** Reiniciar y comprobar que no se repiten.
>
> **4.** Añadir una nueva migración.
>
> **5.** Reiniciar y confirmar que solo se aplica la nueva.

## Evidencia que debemos guardar

- Logs de Flyway.
- Tabla de historial de migraciones.
- Esquema antes/después.

### Fuentes oficiales

- <https://documentation.red-gate.com/flyway>
- <https://documentation.red-gate.com/fd/mysql-277579322.html>
- <https://docs.liquibase.com/community>

# 13. Tecnología principal del frontend — Angular

## Necesidad

Cinco integrantes necesitan trabajar de forma consistente sobre rutas, formularios, servicios, protección de rutas (guards) y consumo de la API.

## Tecnología seleccionada

Angular.

## Alternativa estudiada

React.

## ¿Por qué sí seleccionamos esta opción?

Angular incluye en el mismo ecosistema el manejo de rutas, la inyección de dependencias, formularios y herramientas de línea de comandos (CLI), y además se integra directamente con Ionic Angular. Esto reduce la cantidad de decisiones adicionales que el equipo debe tomar en una entrega corta.

## ¿Por qué no seleccionamos la alternativa?

React es una alternativa madura, pero una aplicación web de una sola página (SPA) creada desde cero suele requerir elegir herramientas adicionales para el manejo de rutas y otras necesidades. Para nuestro equipo eso agrega decisiones y variaciones sin aportar un nuevo requisito de calidad.

## Desventaja o costo aceptado (trade-off)

Angular requiere aprender una estructura y unas convenciones más definidas desde el inicio. A cambio, todos los integrantes trabajan siguiendo una organización común.

## ¿Cómo lo vamos a validar?

> **1.** Inicializar Angular + Ionic.
>
> **2.** Crear varias rutas.
>
> **3.** Crear servicio HTTP al backend.
>
> **4.** Crear guards.
>
> **5.** Compilar con ionic build.
>
> **6.** Ejecutar Playwright.

## Evidencia que debemos guardar

- Build web.
- Estructura de rutas/servicios.
- E2E pasando.

### Fuentes oficiales

- https://angular.dev/
- https://ionicframework.com/angular
- https://react.dev/

# 14. Herramientas web/multiplataforma — Ionic

## Necesidad

La entrega obligatoria es web, pero queremos mantener la posibilidad de reutilizar el frontend para móvil en el futuro.

## Tecnología seleccionada

Ionic con Angular; Capacitor queda como posibilidad futura, no como requisito de la primera entrega.

## Alternativa estudiada

Flutter.

## ¿Por qué sí seleccionamos esta opción?

Ionic está pensado principalmente para tecnologías web y se integra directamente con Angular. Esto permite priorizar la aplicación web y, más adelante, reutilizar gran parte del frontend para Android o iOS sin introducir otro lenguaje.

## ¿Por qué no seleccionamos la alternativa?

Flutter también soporta web y móvil, pero introduciría Dart, otra forma de construir la interfaz de usuario y una curva de aprendizaje adicional para resolver una capacidad móvil que no es requisito oficial.

## Desventaja o costo aceptado (trade-off)

Una aplicación Ionic empaquetada para móvil sigue ejecutando tecnologías web dentro de un componente llamado WebView, por lo que no tiene exactamente el mismo comportamiento gráfico que una aplicación completamente nativa o una hecha con Flutter. Esto es aceptable porque el Marketplace se centra en formularios, catálogo, carrito, mensajería y operaciones CRUD, no en gráficos intensivos.

## ¿Cómo lo vamos a validar?

> **1.** Compilar versión web.
>
> **2.** Probar responsive design en el rango definido.
>
> **3.** Ejecutar Chrome y Edge.
>
> **4.** Ejecutar Playwright.

## Evidencia que debemos guardar

- Build web.
- Evidencia responsive.
- Pruebas Chrome/Edge.

### Fuentes oficiales

- https://ionicframework.com/docs
- https://capacitorjs.com/docs
- https://docs.flutter.dev/platform-integration/web

# 15. Contenedores — Docker

## Necesidad

Necesitamos que frontend, backend, MySQL y servicios auxiliares se ejecuten de forma reproducible en diferentes computadores.

## Tecnología seleccionada

Docker.

## Alternativa estudiada

Podman.

## ¿Por qué sí seleccionamos esta opción?

Docker proporciona imágenes, contenedores, redes, volúmenes y comprobaciones de estado. Además, el mismo ecosistema incluye Docker Swarm, que necesitamos para desplegar la solución en varios computadores.

## ¿Por qué no seleccionamos la alternativa?

Podman es una alternativa válida y puede ejecutarse sin permisos de administrador (modo rootless), pero no incluye Docker Swarm. Usarlo obligaría a añadir otra solución de orquestación además del motor de contenedores.

## Desventaja o costo aceptado (trade-off)

En Windows, Docker Desktop utiliza virtualización. Para reducir problemas de red, la demostración con Swarm se priorizará sobre nodos Linux o máquinas virtuales (VM) Linux ubicadas en computadores físicos distintos.

## ¿Cómo lo vamos a validar?

> **1.** Construir imagen backend.
>
> **2.** Construir imagen frontend.
>
> **3.** Levantar MySQL como contenedor.
>
> **4.** Acceder al sistema.
>
> **5.** Eliminar y recrear contenedores sin instalar software interno manualmente.

## Evidencia que debemos guardar

- Dockerfiles.
- Logs de build.
- docker ps.
- Prueba de acceso.

### Fuentes oficiales

- https://docs.docker.com/engine/
- https://docs.docker.com/subscription-billing/desktop-license/
- https://docs.podman.io/

# 16. Orquestación en varios computadores — Docker Swarm

## Necesidad

La rúbrica exige despliegue en dos o más computadores, réplicas y una demostración donde al derribar una réplica el sistema continúe funcionando.

## Tecnología seleccionada

Docker Swarm.

## Alternativa estudiada

Kubernetes.

## ¿Por qué sí seleccionamos esta opción?

Swarm permite indicar cuántas réplicas de un servicio deben mantenerse, reemplazar tareas cuando fallan y distribuir las solicitudes mediante su malla de enrutamiento (routing mesh). Estas capacidades coinciden directamente con la prueba de disponibilidad (Availability) que debemos demostrar.

## ¿Por qué no seleccionamos la alternativa?

Kubernetes puede hacer lo mismo y ofrece más capacidades, pero introduciría varios conceptos y archivos adicionales —como control plane, Pods, Deployments, Services, Ingress y manifests—. Con dos computadores y poco tiempo, esa complejidad extra no mejora la evidencia requerida.

## Desventaja o costo aceptado (trade-off)

Swarm tiene menos herramientas y una comunidad de uso empresarial menor que Kubernetes. Podría reevaluarse para una plataforma mucho más grande, pero es suficiente para esta entrega distribuida en varios computadores.

## ¿Cómo lo vamos a validar?

> **1.** Configurar un manager y un worker.
>
> **2.** Desplegar 2 réplicas backend.
>
> **3.** Iniciar k6.
>
> **4.** Derribar una réplica.
>
> **5.** Comprobar que sigue respondiendo.
>
> **6.** Comprobar que Swarm crea una tarea sustituta.

## Evidencia que debemos guardar

- docker node ls.
- docker service ls.
- docker service ps.
- Resultados k6 antes/durante/después.

### Fuentes oficiales

- https://docs.docker.com/engine/swarm/services/
- https://docs.docker.com/engine/swarm/ingress/
- https://kubernetes.io/docs/

# 17. CI/CD — GitHub Actions

## Necesidad

Las pruebas y el despliegue deben automatizarse desde el repositorio oficial.

## Tecnología seleccionada

GitHub Actions.

## Alternativa estudiada

Jenkins.

## ¿Por qué sí seleccionamos esta opción?

El repositorio ya está en GitHub. GitHub Actions permite ejecutar flujos automáticos (workflows) cuando se hace un push o un pull request, usar máquinas de ejecución administradas por GitHub o por el equipo (runners), publicar imágenes y encadenar compilación, pruebas y despliegue sin mantener un servidor de CI adicional.

## ¿Por qué no seleccionamos la alternativa?

Jenkins es flexible y de código abierto, pero exige instalar y mantener un servidor, complementos, credenciales y actualizaciones. Ese componente adicional no aporta una ventaja necesaria para este proyecto.

## Desventaja o costo aceptado (trade-off)

El proceso de CI/CD dependerá más de GitHub. Para reducir esa dependencia, los pasos principales seguirán siendo comandos que también puedan ejecutarse localmente.

## ¿Cómo lo vamos a validar?

> **1.** Hacer push.
>
> **2.** Comprobar que se ejecuta build backend.
>
> **3.** Ejecutar pruebas y JaCoCo.
>
> **4.** Construir frontend.
>
> **5.** Ejecutar E2E.
>
> **6.** Construir/publicar imágenes.
>
> **7.** Desplegar desde runner.

## Evidencia que debemos guardar

- Workflow YAML.
- Ejecución verde.
- Logs por job.
- Artifacts de testing/cobertura.

### Fuentes oficiales

- https://docs.github.com/en/actions
- https://docs.github.com/en/actions/concepts/runners/self-hosted-runners
- https://www.jenkins.io/doc/book/pipeline/

# 18. Registro de imágenes — GitHub Container Registry (GHCR)

## Necesidad

Las imágenes construidas por CI necesitan un registro de imágenes desde el cual los nodos de Swarm puedan descargar una versión concreta.

## Tecnología seleccionada

GitHub Container Registry (GHCR).

## Alternativa estudiada

Docker Hub.

## ¿Por qué sí seleccionamos esta opción?

GHCR está integrado con GitHub y permite asociar las imágenes publicadas al repositorio y subirlas desde GitHub Actions usando credenciales del mismo ecosistema.

## ¿Por qué no seleccionamos la alternativa?

Docker Hub también es válido, pero agregaría otra plataforma y otro conjunto de credenciales. Para este proyecto no ofrece una ventaja que compense esa complejidad adicional.

## Desventaja o costo aceptado (trade-off)

El registro de imágenes queda ligado a GitHub. Si GitHub está temporalmente fuera de servicio, no podremos descargar una imagen nueva, aunque las imágenes que ya estén guardadas en los nodos podrán seguir ejecutándose.

## ¿Cómo lo vamos a validar?

> **1.** Construir imagen en CI.
>
> **2.** Etiquetar por versión/commit.
>
> **3.** Publicar en GHCR.
>
> **4.** Hacer docker pull desde otro nodo.
>
> **5.** Desplegar esa etiqueta exacta.

## Evidencia que debemos guardar

- Package en GitHub.
- Logs push/pull.
- Tag de imagen desplegada.

### Fuentes oficiales

- https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
- https://docs.docker.com/docker-hub/

# 19. Herramientas de pruebas backend — JUnit 5 + Spring Boot Test

## Necesidad

Necesitamos pruebas en Java que puedan trabajar con Spring y ejecutarse automáticamente mediante Maven y CI.

## Tecnología seleccionada

JUnit 5/Jupiter + Spring Boot Test.

## Alternativa estudiada

TestNG.

## ¿Por qué sí seleccionamos esta opción?

Spring Boot ya incluye JUnit Jupiter mediante su paquete de pruebas y @SpringBootTest permite iniciar el contexto de la aplicación para comprobar la integración real entre componentes. Por eso encaja directamente con las tecnologías elegidas.

## ¿Por qué no seleccionamos la alternativa?

TestNG también puede cubrir las pruebas, pero añadiría otro framework principal sin que necesitemos específicamente sus funciones adicionales para agrupar pruebas o ejecutarlas en paralelo.

## Desventaja o costo aceptado (trade-off)

Las pruebas que inician todo el contexto de Spring tardan más que las pruebas unitarias. Por eso se usarán únicamente cuando sea necesario comprobar la integración real entre componentes.

## ¿Cómo lo vamos a validar?

> **1.** Configurar pruebas de integración.
>
> **2.** Ejecutar ./mvnw verify.
>
> **3.** Introducir un fallo controlado y comprobar que rompe el build.
>
> **4.** Ejecutar lo mismo en CI.

## Evidencia que debemos guardar

- Número de tests.
- Resultado Maven.
- Job de CI.

### Fuentes oficiales

- https://docs.spring.io/spring-boot/reference/testing/
- https://testng.org/documentation.html

# 20. Base de datos de integración — Testcontainers MySQL

## Necesidad

Las pruebas deben utilizar el mismo motor MySQL que se usará en la demostración, no una base de datos que solo lo imite.

## Tecnología seleccionada

Testcontainers MySQL.

## Alternativa estudiada

H2.

## ¿Por qué sí seleccionamos esta opción?

Testcontainers inicia una instancia real de MySQL dentro de un contenedor temporal y controlado. Así podemos probar los tipos de datos, el SQL, las restricciones, las transacciones y las migraciones sobre el mismo motor que usará el sistema.

## ¿Por qué no seleccionamos la alternativa?

H2 es rápido y útil para pruebas simples, pero no es MySQL. Una prueba puede funcionar en H2 y fallar en MySQL por diferencias de sintaxis, tipos de datos o funciones.

## Desventaja o costo aceptado (trade-off)

Estas pruebas consumirán más recursos y necesitan que Docker esté disponible. Se acepta porque es más importante comprobar el comportamiento sobre el motor real que ahorrar ese costo de ejecución.

## ¿Cómo lo vamos a validar?

> **1.** Ejecutar ./mvnw verify.
>
> **2.** Comprobar que Testcontainers inicia MySQL automáticamente.
>
> **3.** Aplicar Flyway.
>
> **4.** Ejecutar CRUD/transacciones.
>
> **5.** Finalizar y comprobar limpieza del contenedor.

## Evidencia que debemos guardar

- Logs de Testcontainers.
- Logs de Flyway.
- Reporte de tests.

### Fuentes oficiales

- https://java.testcontainers.org/modules/databases/mysql/
- https://java.testcontainers.org/modules/databases/
- https://h2database.com/html/features.html

# 21. Simulación de APIs externas — WireMock

## Necesidad

Las pruebas no pueden depender del estado real de servicios de pagos, logística, correo, notificaciones o IA. También necesitamos poder provocar errores de forma controlada para comprobar cómo responde el sistema ante fallos externos.

## Tecnología seleccionada

WireMock.

## Alternativa estudiada

MockServer.

## ¿Por qué sí seleccionamos esta opción?

WireMock se integra bien con Java/JUnit y permite simular códigos HTTP, contenido de respuestas, demoras, fallos y respuestas que cambian según el estado. Con esto podemos probar tiempos máximos de espera, Circuit Breaker, reintentos y respuestas alternativas cuando un servicio externo falla.

## ¿Por qué no seleccionamos la alternativa?

MockServer también es una alternativa sólida, pero no añade una capacidad necesaria para nuestros escenarios. Cambiar de herramienta no mejoraría las pruebas funcionales que necesitamos cubrir.

## Desventaja o costo aceptado (trade-off)

Una simulación (mock) solo demuestra cómo responde nuestro sistema frente al comportamiento que configuramos; no demuestra que el proveedor real esté disponible. Cuando corresponda, se complementará con pruebas rápidas contra el entorno de pruebas del proveedor (sandbox).

## ¿Cómo lo vamos a validar?

> **1.** Simular 200.
>
> **2.** Simular 400.
>
> **3.** Simular 500.
>
> **4.** Simular delay mayor al timeout.
>
> **5.** Simular respuesta inválida.
>
> **6.** Verificar resultado de negocio en cada caso.

## Evidencia que debemos guardar

- Stubs WireMock.
- Tests automáticos.
- Logs de Circuit Breaker/fallback.

### Fuentes oficiales

- https://wiremock.org/docs/
- https://wiremock.org/docs/simulating-faults/
- https://www.mock-server.com/

# 22. Cobertura — JaCoCo

## Necesidad

La rúbrica exige medir la cobertura del backend y necesitamos una regla automática de calidad (quality gate) que pueda ejecutarse con Maven y CI.

## Tecnología seleccionada

JaCoCo.

## Alternativa estudiada

Cobertura.

## ¿Por qué sí seleccionamos esta opción?

JaCoCo tiene un complemento para Maven, genera reportes y puede hacer fallar la etapa verify cuando no se alcanza un porcentaje mínimo de cobertura por línea, rama, clase o método. También puede generar reportes asociados a pruebas de integración.

## ¿Por qué no seleccionamos la alternativa?

Cobertura también mide cobertura en Java, pero JaCoCo se integra directamente con el ciclo de Maven y ofrece la regla automática de calidad que necesitamos.

## Desventaja o costo aceptado (trade-off)

Tener un porcentaje alto de cobertura no garantiza que las pruebas sean buenas. Por eso el porcentaje debe acompañarse de escenarios de negocio que realmente comprueben el comportamiento esperado.

## ¿Cómo lo vamos a validar?

> **1.** Configurar JaCoCo.
>
> **2.** Ejecutar ./mvnw verify.
>
> **3.** Generar reporte.
>
> **4.** Configurar gate.
>
> **5.** Comprobar que código no cubierto hace fallar el gate.
>
> **6.** Agregar prueba y comprobar que vuelve a pasar.

## Evidencia que debemos guardar

- Reporte HTML/XML.
- Porcentaje final.
- Pipeline mostrando el gate.

### Fuentes oficiales

- https://www.jacoco.org/jacoco/trunk/doc/maven.html
- https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html
- https://cobertura.github.io/cobertura/

# 23. Pruebas de extremo a extremo (E2E) — Playwright

## Necesidad

Necesitamos automatizar flujos completos desde el navegador, atravesando frontend, backend y base de datos, y comprobar los navegadores exigidos por el proyecto.

## Tecnología seleccionada

Playwright.

## Alternativa estudiada

Cypress.

## ¿Por qué sí seleccionamos esta opción?

Playwright puede ejecutarse en integración continua (CI) sin abrir una interfaz gráfica visible, genera reportes y soporta Chromium, Google Chrome y Microsoft Edge. Esto se alinea con los requisitos no funcionales (RNF) definidos para los navegadores.

## ¿Por qué no seleccionamos la alternativa?

Cypress también funciona correctamente, pero no aporta una ventaja necesaria para este alcance. Playwright ya cubre Chrome, Edge y la ejecución automatizada en CI que necesitamos.

## Desventaja o costo aceptado (trade-off)

Las pruebas de extremo a extremo (E2E) tardan más y pueden romperse por cambios de interfaz con mayor facilidad que las pruebas del backend. Por eso se usarán para flujos completos, no para cada validación pequeña.

## ¿Cómo lo vamos a validar?

> **1.** Levantar ambiente de prueba.
>
> **2.** Abrir navegador headless.
>
> **3.** Iniciar sesión.
>
> **4.** Ejecutar flujo de un CU.
>
> **5.** Verificar resultado visible.
>
> **6.** Generar reporte.

## Evidencia que debemos guardar

- Reporte Playwright.
- Screenshots/traces de fallos.
- Job CI.

### Fuentes oficiales

- https://playwright.dev/docs/ci
- https://playwright.dev/docs/browsers
- https://docs.cypress.io/app/continuous-integration/overview

# 24. Pruebas de carga — k6

## Necesidad

Necesitamos medir cuántos usuarios usan el sistema al mismo tiempo, cuántas solicitudes procesa por unidad de tiempo (throughput), el percentil 95 del tiempo de respuesta (P95) y la tasa de errores, y convertir los requisitos de calidad en límites automáticos.

## Tecnología seleccionada

k6.

## Alternativa estudiada

Apache JMeter.

## ¿Por qué sí seleccionamos esta opción?

k6 permite definir las pruebas mediante código JavaScript y establecer límites automáticos (thresholds). Sus scripts son fáciles de guardar en el repositorio y pueden ejecutarse tanto desde la terminal (CLI) como desde CI.

## ¿Por qué no seleccionamos la alternativa?

JMeter es potente y gratuito, pero sus archivos JMX y su forma de trabajo son menos cómodos para revisar cambios como código en un equipo nuevo. k6 nos permite mantener desde el inicio un script de texto sencillo y reproducible.

## Desventaja o costo aceptado (trade-off)

k6 permite detectar que el sistema está lento, pero no identifica automáticamente la causa. Si falla un límite de rendimiento, tendremos que investigar consultas SQL, índices, uso de CPU, caché o integraciones externas.

## ¿Cómo lo vamos a validar?

> **1.** Ejecutar escenario de 50 usuarios.
>
> **2.** Guardar P95, error rate y throughput.
>
> **3.** Ejecutar escenario de 100 usuarios.
>
> **4.** Aplicar thresholds por ASR.
>
> **5.** Comparar resultados.

## Evidencia que debemos guardar

- Scripts k6.
- Output de ambas corridas.
- Resumen de métricas.

### Fuentes oficiales

- https://grafana.com/docs/k6/latest/
- https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/
- https://jmeter.apache.org/usermanual/get-started

# 25. Comprobaciones de estado (health checks) — Spring Boot Actuator

## Necesidad

Necesitamos una ruta estándar que indique el estado del backend para que Swarm y las pruebas de disponibilidad (Availability) sepan si una réplica está funcionando correctamente.

## Tecnología seleccionada

Spring Boot Actuator.

## Alternativa estudiada

Endpoint /health programado manualmente.

## ¿Por qué sí seleccionamos esta opción?

Actuator proporciona rutas de estado ya integradas con Spring y permite añadir información de otros componentes sin tener que diseñar un mecanismo propio desde cero.

## ¿Por qué no seleccionamos la alternativa?

Un controlador creado manualmente que solo responda “OK” demostraría únicamente que ese método pudo ejecutarse. Tendríamos que programar por nuestra cuenta los estados, la información de componentes y su seguridad.

## Desventaja o costo aceptado (trade-off)

Las rutas de administración deben exponerse con cuidado para no mostrar información sensible que no sea necesaria.

## ¿Cómo lo vamos a validar?

> **1.** Iniciar backend.
>
> **2.** Consultar /actuator/health.
>
> **3.** Confirmar UP.
>
> **4.** Configurar Docker healthcheck.
>
> **5.** Provocar fallo de réplica.
>
> **6.** Observar estado y recuperación.

## Evidencia que debemos guardar

- Respuesta Actuator.
- Estado health Docker.
- Logs de recuperación.

### Fuentes oficiales

- https://docs.spring.io/spring-boot/reference/actuator/
- https://docs.spring.io/spring-boot/reference/actuator/monitoring.html

# 26. Resiliencia — Resilience4j

## Necesidad

Las integraciones externas pueden fallar o tardar demasiado. Necesitamos mecanismos como Circuit Breaker, reintentos controlados y límites de tiempo con respuestas alternativas para evitar que un fallo externo bloquee el sistema.

## Tecnología seleccionada

Resilience4j.

## Alternativa estudiada

Spring Retry.

## ¿Por qué sí seleccionamos esta opción?

Resilience4j ofrece módulos para cortar temporalmente las llamadas a un servicio que está fallando (Circuit Breaker), reintentar operaciones (Retry), limitar la cantidad de llamadas (RateLimiter), aislar recursos (Bulkhead) y limitar tiempos de espera (TimeLimiter). Nuestro diseño necesita especialmente Circuit Breaker, no solo reintentos.

## ¿Por qué no seleccionamos la alternativa?

Spring Retry cubre principalmente los reintentos y su proyecto independiente fue archivado. Además, por sí solo no cubre la necesidad principal de Circuit Breaker.

## Desventaja o costo aceptado (trade-off)

Una mala política de reintentos puede ejecutar dos veces una operación que debía ocurrir una sola vez. Por eso pagos, reembolsos y creación de envíos no tendrán reintentos automáticos sin control; cada operación tendrá una política explícita.

## ¿Cómo lo vamos a validar?

> **1.** Con WireMock simular éxitos.
>
> **2.** Simular 500 repetidos hasta abrir Circuit Breaker.
>
> **3.** Comprobar que no se sigue saturando al mock.
>
> **4.** Comprobar fallback.
>
> **5.** Simular recuperación.
>
> **6.** Para retries seguros, contar intentos y verificar máximo.

## Evidencia que debemos guardar

- Tests automáticos.
- Estado/logs del Circuit Breaker.
- Número de intentos.

### Fuentes oficiales

- https://resilience4j.readme.io/docs/circuitbreaker
- https://resilience4j.readme.io/docs/retry
- https://github.com/spring-attic/spring-retry

# 27. Caché — Spring Cache + Caffeine

## Necesidad

Queremos reducir consultas repetidas sobre datos pequeños, frecuentes y no críticos sin agregar un servicio distribuido adicional antes de comprobar que realmente hace falta.

## Tecnología seleccionada

Spring Cache + Caffeine.

## Alternativa estudiada

Redis.

## ¿Por qué sí seleccionamos esta opción?

Caffeine se ejecuta dentro de la misma aplicación Java, Spring Boot puede configurarlo automáticamente y no requiere otro servidor, conexión de red, credenciales o contenedor. Es suficiente como primera optimización para categorías, configuración pública y otros datos que pueden tolerar estar desactualizados durante un periodo corto.

## ¿Por qué no seleccionamos la alternativa?

Redis sería mejor si necesitáramos compartir la misma caché entre todas las réplicas, pero agregaría un servicio distribuido adicional. Primero debemos medir y demostrar que Caffeine no es suficiente.

## Desventaja o costo aceptado (trade-off)

Cada réplica tendrá su propia caché, por lo que durante un periodo corto dos réplicas podrían tener versiones distintas de un dato. La caché nunca se usará como dato definitivo para stock, pagos o el estado final de los pedidos.

## ¿Cómo lo vamos a validar?

> **1.** Elegir consulta no crítica.
>
> **2.** Medir sin caché.
>
> **3.** Habilitar caché.
>
> **4.** Repetir solicitudes.
>
> **5.** Comprobar hit/miss.
>
> **6.** Invalidar/expirar.
>
> **7.** Comprobar recarga.

## Evidencia que debemos guardar

- Métricas/logs hit-miss.
- Tiempos antes/después.
- TTL configurado.
- Lista de datos excluidos de cache.

### Fuentes oficiales

- https://docs.spring.io/spring-boot/reference/io/caching.html
- https://redis.io/docs/latest/develop/use-cases/cache-aside/

# 28. Control de versiones y plataforma — Git + GitHub

## Necesidad

El código, las pruebas, la infraestructura, los scripts y la documentación deben guardar su historial de cambios y conectarse con el proceso de CI/CD.

## Tecnología seleccionada

Git + GitHub.

## Alternativa estudiada

Git + GitLab.

## ¿Por qué sí seleccionamos esta opción?

El repositorio oficial ya está en GitHub y otras decisiones dependen del mismo ecosistema: GitHub Actions, GHCR, solicitudes de cambio (pull requests) y una máquina de ejecución propia (self-hosted runner).

## ¿Por qué no seleccionamos la alternativa?

GitLab también podría cubrir el repositorio y CI/CD, pero migrar no mejora ningún requisito de calidad y obligaría a rehacer permisos, flujos automáticos y el registro de imágenes.

## Desventaja o costo aceptado (trade-off)

El flujo de desarrollo depende de una plataforma ofrecida como servicio en la nube (SaaS). Si GitHub presenta una caída temporal, la aplicación que ya está desplegada seguirá funcionando; lo afectado sería construir o desplegar una nueva versión.

## ¿Cómo lo vamos a validar?

> **1.** Hacer push a rama.
>
> **2.** Abrir PR.
>
> **3.** Ejecutar checks automáticos.
>
> **4.** Merge con checks.
>
> **5.** Crear tag estable.

## Evidencia que debemos guardar

- Historial Git.
- PR con checks.
- Tag de release.

### Fuentes oficiales

- https://docs.github.com/en/actions
- https://docs.gitlab.com/ci/

# 29. Arquitectura de despliegue

Usuarios  
\|  
Docker Swarm routing mesh  
\|  
+-- Backend A (PC 1)  
+-- Backend B (PC 2)  
\|  
MySQL  
  
Frontend Angular + Ionic compilado como web  
Servicios externos/simulados según ambiente

El frontend se compila como archivos web y se sirve dentro de un contenedor. El backend es un único monolito del que se ejecutan varias réplicas. MySQL será una única instancia persistente en la primera entrega. Las integraciones externas se conectan mediante adaptadores y, durante las pruebas, esos servicios se reemplazan por simulaciones con WireMock.

## 30. Availability — tácticas, patrones y decisiones

### Objetivo

Para el Marketplace, Availability significa que el sistema pueda detectar fallos, recuperarse cuando sea posible y evitar que una falla secundaria detenga las operaciones principales.

Las funciones más importantes para este atributo son autenticación, catálogo, carrito, compra, pedidos e inventario. Funciones secundarias como recomendaciones, analítica o notificaciones externas pueden degradarse temporalmente sin impedir que el núcleo del Marketplace continúe funcionando.

A partir de las tácticas y patrones estudiados, no implementaremos todos. Se seleccionaron únicamente aquellos que responden a fallos que realmente pueden presentarse en nuestra arquitectura y que pueden demostrarse durante la entrega.

### Tácticas y patrones seleccionados

#### 1. Monitoreo del estado y recuperación automática

Utilizaremos **Monitoring / Health Checks** para conocer si las réplicas del backend se encuentran funcionando correctamente.

Spring Boot Actuator proporcionará el estado de salud del backend y Docker utilizará esa información para conocer el estado de los contenedores.

Además, tendremos dos réplicas activas del backend en Docker Swarm. Si una de ellas falla, Swarm intentará recuperar el número configurado de réplicas creando una tarea sustituta.

Esta decisión combina detección del fallo con recuperación automática, sin requerir reiniciar manualmente todo el Marketplace.

#### 2. Detección y manejo controlado de errores

Utilizaremos **Exception Detection** y **Exception Handling** para evitar que un error controlable provoque la caída completa del proceso.

Cuando una integración externa responda con un error, tarde demasiado o devuelva información inválida, el backend deberá reconocer esa situación y aplicar el comportamiento definido para ese caso.

También realizaremos **Sanity Checking** sobre respuestas importantes de servicios externos. Que un proveedor responda no significa necesariamente que su respuesta sea válida para nuestro negocio.

Por ejemplo, no se debe aceptar como correcto un cambio de estado logístico incompatible con el estado actual del pedido.

#### 3. Retry controlado y Circuit Breaker

Utilizaremos **Retry controlado** únicamente en operaciones donde repetir una solicitud sea seguro.

No se realizarán reintentos automáticos sin control sobre operaciones sensibles como pagos, reembolsos o creación de envíos, ya que podrían producir operaciones duplicadas.

Para servicios externos que fallen repetidamente utilizaremos el patrón **Circuit Breaker**, implementado mediante Resilience4j.

Cuando un servicio externo acumule fallos, el Circuit Breaker dejará temporalmente de enviar nuevas solicitudes. De esta manera se evita seguir saturando un servicio que ya sabemos que está presentando problemas y se permite intentar nuevamente cuando corresponda.

#### 4. Graceful Degradation

Utilizaremos **Graceful Degradation** para que el fallo de funciones secundarias no detenga las operaciones principales.

Los comportamientos definidos son:

- si falla el servicio de recomendaciones, se podrán mostrar productos generales;
- si falla la analítica, la compra podrá continuar;
- si falla una notificación externa, se conservará la notificación interna;
- si falla temporalmente la integración logística, se mantendrá el último estado válido mientras se permite una recuperación segura.

La degradación no significa ignorar el fallo. El error deberá registrarse y la funcionalidad afectada deberá recuperarse posteriormente.

#### 5. Consistencia de operaciones críticas

Para operaciones internas utilizaremos **transacciones ACID**, de forma que un conjunto de cambios relacionados se complete correctamente o se revierta si ocurre un error.

Para operaciones que involucren proveedores externos utilizaremos **identificadores de operación e idempotencia** para reconocer una solicitud que ya fue procesada y evitar efectos duplicados.

En procesos como una compra que combina inventario, pago y creación del pedido se utilizará el patrón **Saga simplificada**.

Por ejemplo:

reservar inventario → solicitar pago → pago aprobado → confirmar pedido

Si el pago es rechazado:

pago rechazado → liberar reserva

La Saga permite aplicar una acción de compensación cuando no es posible utilizar una única transacción de base de datos para todo el proceso.

### Tácticas y patrones analizados pero no seleccionados

#### Ping / Echo

Permite preguntar a un componente si continúa respondiendo.

No se implementará como mecanismo independiente porque Spring Boot Actuator proporciona health checks con información más útil que un simple mensaje de respuesta.

#### Timestamp como mecanismo principal

Los timestamps pueden ayudar a conocer cuándo ocurrió un evento, pero no se utilizarán por sí solos para evitar operaciones duplicadas.

Para operaciones sensibles utilizaremos identificadores e idempotencia, que permiten reconocer directamente si una operación ya fue procesada. Los timestamps podrán seguir formando parte de los registros y eventos cuando sean necesarios.

#### Rollback como táctica independiente de Availability

No se utilizará como mecanismo general separado.

Para cambios internos, la reversión será responsabilidad de las transacciones ACID. Para procesos que involucren servicios externos utilizaremos compensaciones mediante Saga.

El rollback de una versión completa del sistema pertenece a Deployability y se trata por separado.

#### Exception Prevention

Las validaciones normales del sistema seguirán existiendo para evitar datos inválidos, cantidades negativas o estados imposibles.

Sin embargo, no se manejará como una táctica principal e independiente de Availability, ya que el atributo se concentrará en detección, manejo y recuperación frente a fallos que efectivamente ocurran.

#### Recuperación escalada

No se implementará un mecanismo complejo que ejecute diferentes niveles de recuperación progresivamente.

Docker Swarm ya proporcionará la recuperación necesaria para las réplicas del backend. Si la aplicación falla, Swarm intentará sustituir la tarea afectada sin reiniciar deliberadamente componentes adicionales.

#### Incrementar estados competentes

Durante el diseño de los casos de uso se contemplarán estados de error como pago rechazado, devolución fallida o servicio logístico no disponible.

Sin embargo, no se implementará como una táctica arquitectónica independiente, sino como parte del modelado de estados, manejo de excepciones y Saga.

#### Heartbeat continuo

No se utilizará un sistema donde los componentes estén enviando señales permanentemente para demostrar que siguen funcionando.

Para nuestra escala, los health checks son suficientes y requieren menos mecanismos adicionales.

#### Voting

No se utilizará votación entre múltiples componentes que ejecuten la misma operación y comparen sus resultados.

Las réplicas del backend atienden solicitudes diferentes; no ejecutaremos cada operación varias veces para escoger la respuesta de la mayoría.

#### Hot Spare, Warm Spare y Cold Spare

No mantendremos copias de respaldo en espera bajo estos modelos.

Las dos réplicas de backend que utilizaremos estarán activas y podrán atender tráfico. Por lo tanto, nuestra estrategia es diferente a mantener una réplica esperando únicamente para reemplazar otra.

#### Triple Modular Redundancy — TMR

No ejecutaremos cada operación en tres sistemas distintos para comparar las respuestas.

Este patrón se justifica principalmente en sistemas donde una falla puede tener consecuencias extremadamente críticas. Para el Marketplace agregaría un costo y una complejidad que no corresponden a nuestra escala.

#### Modelo predictivo de fallos

No intentaremos predecir fallos utilizando modelos basados en datos históricos.

El proyecto no dispone de suficiente historial de fallos reales y el objetivo de esta entrega es detectar y manejar correctamente los errores, no predecirlos antes de que ocurran.

#### Shadow

No mantendremos una réplica recuperada atendiendo tráfico oculto antes de reincorporarla al servicio.

Swarm administrará directamente las réplicas activas y su recuperación.

#### Resincronización de réplicas

No tendremos múltiples réplicas independientes de MySQL que deban sincronizar su información después de una falla.

La primera entrega tendrá una única instancia persistente de MySQL. Por lo tanto, esta táctica no es necesaria.

#### Nonstop Forwarding

No se utilizará porque corresponde principalmente a infraestructura de red que debe continuar reenviando paquetes aunque falle el componente encargado de calcular rutas.

No soluciona un problema arquitectónico propio de nuestro Marketplace.

#### Two-Phase Commit — 2PC

No utilizaremos una transacción distribuida que obligue a MySQL y a proveedores externos a confirmar conjuntamente una operación.

No controlamos servicios externos como pagos o logística y no podemos obligarlos a participar en nuestra transacción.

En esos procesos utilizaremos Saga, estados e idempotencia.

#### ISSU — In-Service Software Upgrade

No necesitamos garantizar que el software pueda actualizarse sin ninguna interrupción de servicio.

El Marketplace no tendrá en esta entrega una operación productiva permanente 24/7. Las actualizaciones y recuperación de versiones se manejarán mediante las decisiones de Deployability.

#### Alta disponibilidad automática de MySQL

No se implementará en la primera entrega un clúster MySQL con replicación y failover automático.

Agregar réplicas de base de datos, elección de un nodo primario y coordinación del failover implicaría una complejidad alta frente al alcance de la entrega.

Esta limitación queda documentada: si la única instancia MySQL falla, las operaciones que necesiten persistencia no podrán continuar hasta recuperar la base de datos.

# 31. Prueba cuantitativa de Availability

> **1.** Tener dos nodos Swarm disponibles.
>
> **2.** Desplegar dos réplicas backend y confirmar estado saludable.
>
> **3.** Iniciar un escenario k6 de tráfico continuo.
>
> **4.** Registrar métricas base.
>
> **5.** Derribar manualmente una réplica backend.
>
> **6.** Mantener k6 ejecutándose y registrar solicitudes exitosas, errores, P95 y periodo de recuperación.
>
> **7.** Comprobar que la otra réplica continúa respondiendo.
>
> **8.** Comprobar que Swarm crea una tarea sustituta y vuelve al número deseado de réplicas.
>
> **9.** Guardar logs, comandos y resultados cuantitativos.

**Nota:** La prueba de Availability de primera entrega se centra en la caída de una réplica de backend. MySQL no tendrá failover automático en esta primera entrega; esa limitación se documenta como riesgo técnico conocido.

# 32. Alta disponibilidad y recuperación de MySQL

Para la primera entrega se utilizará una única instancia persistente de MySQL. No se implementará un clúster con cambio automático a otra instancia cuando falle la principal (failover), porque eso requeriría configurar replicación, elegir cuál instancia queda como principal y coordinar el cambio ante una falla. Ese esfuerzo no corresponde a la prueba de Availability solicitada para esta entrega.

- Volumen persistente para conservar los datos aunque se recree el contenedor.
- Copia de seguridad lógica con mysqldump.
- Restauración de la copia mediante el cliente mysql.
- Migraciones Flyway versionadas.
- Procedimiento reproducible para recuperar la base de datos.
- Riesgo documentado: si MySQL deja de estar disponible, no podrán ejecutarse las operaciones que necesitan guardar o consultar datos.

# 34. Saga simplificada e idempotencia

Los procesos que combinan cambios internos con proveedores externos se manejarán por pasos. Cada paso guardará su estado, evitará repetir una misma operación (idempotencia) y podrá ejecutar una acción de compensación si algo falla. Así evitamos usar una confirmación distribuida en dos fases (Two-Phase Commit) con sistemas externos que no controlamos.

reservar inventario  
-> solicitar pago  
-> pago aprobado -> confirmar pedido  
-> pago rechazado -> liberar reserva

No se harán reintentos automáticos sin comprobar el estado de pagos, reembolsos o creación de envíos. Cuando un proveedor admita una clave idempotente —un identificador que permite reconocer una solicitud repetida— se utilizará para evitar duplicados; de lo contrario, el backend deberá guardar y reconocer el identificador de la operación.

**35. Performance — tácticas, patrones y decisiones**

**Objetivo**

Para el Marketplace, Performance significa mantener tiempos de respuesta aceptables cuando varios usuarios utilicen el sistema al mismo tiempo, sin introducir infraestructura cuya complejidad no se justifique para la escala esperada.

La escala objetivo es de aproximadamente 100 usuarios concurrentes, alrededor de 1.000 productos, importaciones de hasta 500 filas y análisis sobre aproximadamente 10.000 pedidos simulados.

A partir de las alternativas estudiadas se seleccionaron tácticas orientadas primero a mejorar la eficiencia del sistema y después a distribuir carga cuando sea necesario.

**Tácticas y patrones seleccionados**

**1. Aumentar eficiencia**

La primera estrategia será reducir el trabajo innecesario realizado por el sistema antes de agregar nueva infraestructura.

Se revisarán especialmente las consultas ejecutadas mediante JPA y el SQL generado para los endpoints que incumplan sus objetivos de tiempo.

No se optimizarán consultas únicamente por intuición. Las mejoras se aplicarán cuando las pruebas de Performance identifiquen un problema concreto.

**2. Índices en base de datos**

Se utilizarán índices MySQL para consultas frecuentes o que las mediciones demuestren que son lentas.

Los índices permiten localizar información sin recorrer innecesariamente todos los registros disponibles.

No se crearán índices para cada columna de manera indiscriminada, ya que también tienen costo de almacenamiento y mantenimiento. Su incorporación deberá estar relacionada con consultas reales del Marketplace.

**3. Caching**

Utilizaremos caché para información de lectura frecuente, poco cambiante y no crítica.

Spring Cache + Caffeine permitirá almacenar temporalmente datos dentro de cada instancia del backend.

También podrá utilizarse la caché normal del navegador para recursos web estáticos.

No se utilizará la caché como fuente de verdad para:

- inventario;
- pagos;
- estado final de pedidos;
- cualquier dato cuya desactualización pueda producir una operación incorrecta.

Cada réplica tendrá su propia caché, por lo que aceptamos que durante un periodo corto puedan existir diferencias entre los datos cacheados de cada instancia.

**4. Concurrencia normal del backend**

El backend podrá atender múltiples solicitudes de diferentes usuarios de manera concurrente utilizando las capacidades normales de Spring Boot y del servidor web.

No implementaremos procesamiento paralelo especial sobre operaciones sensibles como compra, pago, inventario, cancelaciones o reembolsos, ya que comparten información que debe mantenerse consistente.

Si las pruebas muestran posteriormente que tareas independientes como analítica o importaciones necesitan un tratamiento diferente, se evaluará específicamente.

**5. Múltiples nodos y réplicas del backend**

Se ejecutarán dos réplicas del mismo backend mediante Docker Swarm.

Esto permite que las solicitudes no dependan de una única instancia y que el trabajo pueda repartirse entre las réplicas disponibles.

El backend seguirá siendo un monolito modular. Tener varias instancias del mismo backend no significa convertirlo en microservicios.

**6. Load Balancing y programación de recursos**

Utilizaremos el comportamiento de Docker Swarm para distribuir solicitudes entre las réplicas disponibles y programar las tareas en los nodos del clúster.

El routing mesh de Swarm actuará como mecanismo de distribución de tráfico entre las instancias activas del servicio.

De esta manera utilizamos el patrón de **Load Balancer** sin agregar un producto adicional únicamente para esta función.

**Tácticas y patrones analizados pero no seleccionados**

**Administrar peticiones de trabajo mediante un mecanismo adicional**

No se implementará inicialmente un componente específico de admisión o control de trabajos antes de que las solicitudes lleguen al backend.

Para la escala objetivo se utilizarán las capacidades normales del servidor y las réplicas. Si las pruebas muestran saturación real, esta decisión podrá reevaluarse.

**Limitar respuesta a eventos**

No implementaremos inicialmente un sistema general que encole o rechace solicitudes automáticamente cuando el Marketplace reciba alta carga.

Primero mediremos el comportamiento con hasta 100 usuarios concurrentes. No queremos introducir políticas de rechazo sin evidencia de que sean necesarias.

**Priorizar eventos**

No se implementará un sistema formal de prioridades que permita al servidor procesar una compra antes que una solicitud de analítica.

Aunque conceptualmente una compra tiene mayor importancia de negocio, crear un planificador de prioridades agregaría complejidad adicional.

El diseño buscará en cambio que las funciones secundarias no bloqueen innecesariamente las operaciones críticas.

**Limitar tamaño de colas**

No se implementará una cola arquitectónica central para las tareas del Marketplace en esta primera entrega.

Al no existir esa cola, tampoco necesitamos una política específica para limitar su tamaño.

Si posteriormente se incorpora procesamiento asíncrono con colas, este límite deberá definirse en ese momento.

**Procesamiento en cola / mensajería distribuida**

Se estudió enviar trabajos secundarios a una cola para procesarlos después.

No utilizaremos inicialmente Kafka, RabbitMQ u otra plataforma distribuida de mensajería porque los ASR actuales no justifican agregar esa infraestructura.

Una operación sencilla podrá realizarse de forma asíncrona dentro de la aplicación si es necesario, pero no se agregará un sistema distribuido de colas sin una necesidad demostrada.

**Throttling**

Se analizó limitar la cantidad de solicitudes que puede realizar un usuario o servicio durante un periodo determinado.

No se implementará como patrón general en la primera entrega porque nuestros ASR se concentran en aproximadamente 100 usuarios concurrentes y no tenemos todavía evidencia de abuso o saturación que lo justifique.

Además, un throttling coordinado entre varias réplicas puede requerir estado compartido adicional.

Si las pruebas de carga o escenarios de seguridad demuestran posteriormente que determinadas funciones necesitan límites, podrá reevaluarse.

**Bucket4j**

Bucket4j fue considerado como una posible herramienta para implementar Throttling.

Como finalmente no se seleccionó Throttling para esta entrega, tampoco se incorporará Bucket4j inicialmente.

**Redis**

Redis fue considerado como alternativa para disponer de una caché compartida entre todas las réplicas.

No se utilizará inicialmente porque Spring Cache + Caffeine cubre la primera necesidad de caché sin agregar otro servidor, red y persistencia adicional.

Redis podrá reevaluarse si las mediciones demuestran que tener cachés independientes por réplica genera un problema real.

**Map-Reduce**

No se utilizará procesamiento Map-Reduce.

El análisis previsto sobre aproximadamente 10.000 pedidos es demasiado pequeño para justificar dividir el procesamiento entre múltiples nodos y posteriormente combinar los resultados.

La infraestructura necesaria sería más compleja que el problema que queremos resolver.

**Denormalización desde el inicio**

No duplicaremos información de la base de datos deliberadamente para acelerar consultas desde el comienzo.

Primero utilizaremos consultas adecuadas, índices y caché.

La denormalización solo tendría sentido si una medición concreta demuestra posteriormente que una consulta crítica no puede cumplir sus objetivos con las estrategias seleccionadas.

**Replicación de base de datos para Performance**

No se mantendrán varias copias de MySQL únicamente para repartir consultas.

La primera entrega utiliza una sola instancia persistente de MySQL.

Agregar replicación introduciría sincronización y operación adicional que no está justificada por la escala actual.

**Aumentar hardware como solución principal**

Agregar más CPU o memoria puede aumentar la capacidad del sistema, pero no será nuestra primera estrategia de Performance.

Primero se revisarán consultas, índices, caché y distribución entre réplicas.

Aumentar recursos sin conocer la causa del problema podría ocultar una implementación ineficiente.

**Limitar CPU de procesos**

No se establecerán límites de CPU como táctica principal de Performance.

Nuestro objetivo es cumplir tiempos de respuesta y soportar la carga definida, no restringir artificialmente cuánto procesador puede utilizar un proceso.

**Co-ubicar todos los componentes**

Colocar frontend, backend y base de datos en una sola máquina podría reducir parte de la latencia de red.

No lo utilizaremos como estrategia principal porque la arquitectura debe poder ejecutarse en varios computadores y demostrar un despliegue distribuido.

**PostgreSQL VACUUM**

Esta opción apareció durante el estudio preliminar cuando PostgreSQL todavía era considerado como motor de base de datos.

La decisión final del proyecto utiliza MySQL, por lo que PostgreSQL VACUUM ya no aplica a la arquitectura seleccionada.

# 36. Prueba cuantitativa de Performance

Las pruebas k6 deben mapear los thresholds a los ASR, no usar un único número para todo.

- Catálogo/detalle: P95 <= 3 s.
- Carrito/favoritos/perfil/direcciones: P95 <= 2 s.
- Checkout interno: <= 3 s antes de proveedor externo.
- Mensajería/notificaciones internas: 95 % <= 5 s.
- Recomendaciones: 95 % <= 5 s.
- Prueba 100 usuarios concurrentes: tasa de error < 2 % y P95 global objetivo <= 4 s, sin reemplazar los límites específicos por endpoint.

> **1.** Ejecutar escenario de 50 usuarios.
>
> **2.** Guardar throughput, P95 y errores.
>
> **3.** Ejecutar escenario de 100 usuarios.
>
> **4.** Comparar.
>
> **5.** Si falla un threshold, identificar endpoint, revisar logs/SQL/índices/cache y repetir exactamente el mismo script.

**36.1. Deployability — tácticas, patrones y decisiones**

**Objetivo**

Para el Marketplace, Deployability significa poder llevar una versión del sistema a otro computador y ponerla a funcionar de manera repetible y predecible, evitando depender de configuraciones manuales difíciles de reproducir.

La solución debe permitir desplegar frontend, backend, base de datos y componentes auxiliares en el ambiente de demostración y ejecutar el sistema en más de un computador.

A partir de las alternativas estudiadas se seleccionaron mecanismos que permitan automatizar el despliegue y regresar a una versión estable si una nueva versión presenta problemas.

**Tácticas y patrones seleccionados**

**1. Script Deployment Commands**

El despliegue tendrá un único punto de entrada mediante un script, por ejemplo ./deploy.sh.

El objetivo es evitar un procedimiento donde una persona tenga que abrir diferentes terminales e iniciar manualmente frontend, backend y base de datos.

El script deberá validar el ambiente necesario, obtener las imágenes correspondientes, desplegar el sistema y verificar posteriormente que los componentes estén funcionando.

**2. Package Dependencies mediante contenedores**

Los componentes se empaquetarán utilizando Docker.

De esta forma las dependencias necesarias para ejecutar cada componente quedan definidas dentro de sus imágenes y no dependen de instalar manualmente diferentes versiones de software en cada computador.

Esta decisión busca reducir el problema de que una versión funcione solamente en el computador donde fue desarrollada.

Docker Swarm se utilizará para el despliegue multi-host de la versión final.

**3. Separación de ambientes**

Mantendremos ambientes con propósitos diferentes:

**Development:** entorno utilizado por cada integrante mientras desarrolla.

**Integration:** GitHub Actions ejecuta compilación, pruebas y verificaciones automáticas.

**Staging/Test:** ambiente limpio donde se ejecutan pruebas que necesitan el sistema completo.

**Demo:** despliegue distribuido mediante Swarm en al menos dos computadores físicos o máquinas virtuales ubicadas en computadores físicos diferentes.

La separación permite detectar problemas antes de considerar una versión lista para la demostración final.

**4. Versionado y Rollback**

Las imágenes desplegadas tendrán versiones identificables y no dependeremos únicamente de la etiqueta latest.

Se conservará la versión estable anterior.

Si una nueva versión presenta un problema durante el despliegue, podremos volver a desplegar la versión anterior conocida.

Las migraciones de base de datos también deberán diseñarse teniendo en cuenta esta estrategia para evitar que un cambio incompatible impida regresar el backend a una versión previa.

**5. Rolling Update cuando sea viable**

Docker Swarm permite actualizar gradualmente las réplicas de un servicio.

Cuando sea viable utilizaremos esta capacidad para sustituir progresivamente las instancias de una versión por las de la nueva versión, evitando reemplazar todas simultáneamente.

Para la primera entrega no se requiere construir una plataforma avanzada de despliegue progresivo; utilizaremos las capacidades proporcionadas por el orquestador seleccionado.

**Tácticas, patrones y alternativas analizados pero no seleccionados**

**Toggle Features / Kill Switch**

Se analizó permitir desactivar una funcionalidad específica sin desplegar nuevamente toda la aplicación.

Podría ser útil para apagar temporalmente recomendaciones o analítica, pero implicaría implementar y administrar estados de activación para las funcionalidades.

No se considera necesario para los ASR principales de esta primera entrega.

La tolerancia frente al fallo de esas funciones se resolverá principalmente mediante Graceful Degradation dentro de Availability.

**Blue/Green Deployment**

Blue/Green mantiene dos ambientes completos: uno con la versión actual y otro con la versión nueva.

Cuando la versión nueva está validada, el tráfico cambia de un ambiente al otro.

No se utilizará porque requiere mantener dos despliegues completos simultáneamente y una forma de cambiar el tráfico entre ellos.

Para nuestra escala, versionado, rollback y las actualizaciones de Swarm son suficientes.

**Scaled Rollouts**

No desplegaremos una versión nueva únicamente a un porcentaje de los usuarios para aumentar progresivamente su alcance.

No tendremos una población productiva real cuya exposición deba controlarse gradualmente.

**Canary Testing / Canary Deployment**

No se utilizará un grupo pequeño de usuarios reales para recibir primero una nueva versión antes de entregarla al resto.

Los usuarios de la entrega serán simulados y no existe tráfico productivo permanente que permita obtener información representativa de un grupo canario.

**A/B Testing**

No mantendremos dos versiones de una funcionalidad para comparar el comportamiento de diferentes grupos de usuarios.

El objetivo de esta entrega es demostrar que la arquitectura cumple los requisitos y atributos de calidad, no realizar experimentos de producto sobre usuarios reales.

**Service Registry**

No implementaremos un registro independiente donde los servicios se inscriban y descubran dinámicamente entre sí.

Tenemos un monolito modular replicado, no un conjunto grande de microservicios que necesiten descubrimiento dinámico.

Docker Swarm ya administra la ubicación de las tareas que forman parte de sus servicios.

**Traffic Splitting entre versiones**

No dividiremos deliberadamente el tráfico para que una parte llegue a una versión y otra parte a una versión diferente.

No necesitamos ejecutar experimentos Canary, A/B o Blue/Green.

Esto es diferente del reparto normal de tráfico entre réplicas equivalentes del mismo backend, que sí será realizado por Swarm.

**Load Balancing como estrategia específica de Deployability**

No agregaremos un balanceador adicional para gestionar diferentes versiones durante un despliegue.

El reparto normal de solicitudes entre réplicas del mismo backend sí se utilizará y forma parte de las decisiones de Performance y Availability.

**Máquinas virtuales como patrón arquitectónico**

Las máquinas virtuales no se consideran una táctica o patrón obligatorio de nuestra arquitectura.

Podrán utilizarse como mecanismo para disponer de ambientes limpios y demostrar que el sistema puede ejecutarse en máquinas diferentes.

La arquitectura no dependerá de una VM específica para funcionar.

**Docker Compose como mecanismo final de despliegue**

Docker Compose fue considerado inicialmente para levantar varios componentes con un único comando en un computador.

La decisión final utiliza Docker Swarm porque la entrega necesita demostrar ejecución en múltiples máquinas y réplicas.

Compose puede seguir siendo útil durante desarrollo local si el equipo lo necesita, pero no será el mecanismo de orquestación del despliegue final.

**Continuous Integration sin Continuous Deployment**

Durante el análisis inicial se consideró limitar la automatización a CI.

La decisión final va más allá: GitHub Actions automatizará compilación y pruebas, y el despliegue final podrá realizarse mediante un self-hosted runner con acceso al manager de Swarm.

Por lo tanto, sí se implementará un flujo de Continuous Deployment para el ambiente definido para la entrega.

**Herramientas asociadas a las tácticas seleccionadas**

**Docker:** empaquetado reproducible de los componentes.

**Docker Swarm:** despliegue multi-host, réplicas y actualizaciones de servicios.

**Git y GitHub:** control y versionado del código.

**GitHub Actions:** automatización del pipeline CI/CD.

**GHCR:** almacenamiento de imágenes Docker versionadas.

**Tags de Git e imágenes versionadas:** identificación de versiones estables y soporte para rollback.

**Flyway:** migraciones versionadas de la base de datos.

**Self-hosted runner:** ejecución automatizada del despliegue desde una máquina con acceso al Swarm.

Estas herramientas son el mecanismo de implementación. Las decisiones arquitectónicas principales son el empaquetado reproducible, automatización del despliegue, separación de ambientes, versionado, rollback y actualización gradual.

# 37. Pipeline CI/CD definitivo

Push / Pull Request  
-> Checkout  
-> Setup Java 21  
-> ./mvnw verify  
-> integration tests  
-> Testcontainers MySQL  
-> JaCoCo gate  
-> npm ci  
-> Angular/Ionic build  
-> levantar ambiente de prueba  
-> Playwright E2E  
-> Docker build  
-> publicar imágenes en GHCR  
-> self-hosted runner  
-> docker stack deploy  
-> health check  
-> smoke test

Una prueba obligatoria no se considera automatizada si una persona debe entrar manualmente a una máquina y ejecutarla para que el flujo de CI/CD pueda continuar.

# 38. Runner propio para despliegue continuo (self-hosted runner)

Las tareas de integración continua que no necesitan acceso a la red local pueden ejecutarse en máquinas administradas por GitHub. El despliegue final utilizará una máquina de ejecución propia (self-hosted runner) en el nodo manager, porque necesita acceso directo al servicio de Docker (Docker daemon) y al Swarm local.

- GitHub documenta que los self-hosted runners no generan cargos de ejecución en GitHub Actions; el equipo asume el costo y el mantenimiento de la máquina utilizada.
- El runner debe protegerse: no debe ejecutar flujos automáticos (workflows) que no sean confiables y debe tener únicamente los permisos necesarios.

# 39. Script único de despliegue

Existirá un único punto de entrada, por ejemplo ./deploy.sh, ejecutado desde el manager.

> **1.** Validar Docker/Swarm y variables requeridas.
>
> **2.** Autenticar registry cuando corresponda.
>
> **3.** Obtener imágenes versionadas.
>
> **4.** Ejecutar docker stack deploy.
>
> **5.** Esperar health checks.
>
> **6.** Ejecutar smoke test.
>
> **7.** Salir con código distinto de cero si algo falla.

**Nota:** No es aceptable depender de “abre una terminal para backend, otra para frontend y otra para la base de datos”. El objetivo es que el sistema completo pueda iniciarse de forma repetible desde un único flujo de despliegue.

# 40. Estrategia de pruebas backend

HTTP request  
-> Controller real  
-> Service real  
-> Repository real  
-> MySQL Testcontainers  
+ WireMock para integración externa

La prueba debe verificar tanto la respuesta HTTP como el estado guardado en la base de datos. Para cumplir la rúbrica de la forma más estricta, se buscará 100 % de cobertura de integración sobre el código de aplicación implementado, salvo exclusiones justificadas y aceptadas explícitamente por el profesor. JaCoCo deberá mostrar de forma separada o claramente identificable la cobertura obtenida por la suite de integración.

**Nota:** Si existe oportunidad, debe confirmarse con el profesor el significado exacto de “100 % de cobertura de pruebas de integración sobre el backend”. Mientras no haya aclaración, se aplicará la interpretación más estricta y no se excluirá lógica real solo para aumentar artificialmente el porcentaje de cobertura.

# 41. Estrategia de pruebas funcionales E2E

Playwright  
-> Angular/Ionic  
-> Spring Boot  
-> MySQL

Playwright comprobará los flujos completos desde la perspectiva del usuario. Las pruebas formarán parte del flujo automatizado de CI/CD y no dependerán de pasos manuales. Los navegadores exigidos por el requisito no funcional (RNF) son Chrome y Edge, y Playwright permite ejecutar pruebas específicamente sobre ambos.

# 42. Ambientes

| Ambiente     | Uso                                                                                                    |
|--------------|--------------------------------------------------------------------------------------------------------|
| Desarrollo   | Computador de cada integrante; servicios locales/Docker según necesidad.                               |
| Integración  | GitHub Actions; pruebas backend, cobertura, build frontend.                                            |
| Pruebas      | Ambiente limpio levantado automáticamente para E2E/smoke tests.                                        |
| Demostración | Swarm distribuido en mínimo dos computadores físicos o VMs ubicadas en computadores físicos distintos. |

# 43. Regreso a una versión anterior (rollback) y versionado

- Versionar las imágenes con etiquetas (tags) que no cambien y no depender únicamente de latest.
- Conservar la versión estable anterior.
- Usar actualizaciones graduales (rolling) de Swarm cuando sea viable.
- Si el despliegue falla, volver a desplegar la versión estable anterior (rollback).
- Diseñar las migraciones de base de datos de forma que, cuando sea posible, una versión anterior del backend todavía pueda funcionar. Esto facilita volver atrás si un despliegue falla.

# 44. Tecnologías y patrones que NO se implementarán inicialmente

| No se implementa ahora                             | Razón                                                                                                                                                   |
|----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Kubernetes                                         | Swarm cubre el despliegue en varios computadores, las réplicas y la recuperación con menor complejidad.                                                 |
| Redis                                              | Caffeine es suficiente inicialmente para datos no críticos; Redis se reevaluará si todas las réplicas necesitan compartir la misma caché.               |
| Kafka/RabbitMQ                                     | No son necesarios para los requisitos de calidad que debemos demostrar y agregarían más infraestructura distribuida.                                    |
| Map-Reduce                                         | 10.000 pedidos simulados no justifican esa infraestructura.                                                                                             |
| MySQL con alta disponibilidad automática           | Agrega demasiada complejidad para la primera entrega; la prueba explícita de disponibilidad (Availability) se realizará sobre las réplicas del backend. |
| Despliegue canario (canary deployment)             | No existe una cantidad real de usuarios en producción que justifique desplegar una versión nueva solo a un grupo pequeño.                               |
| Pruebas A/B                                        | No hay suficientes usuarios reales en producción para realizar un experimento controlado entre dos versiones.                                           |
| Limitación distribuida de solicitudes (throttling) | Requeriría compartir información adicional entre réplicas y no corresponde a un requisito de calidad prioritario de esta entrega.                       |

# 45. Estado actual del repositorio y cambios necesarios

La auditoría inicial del repositorio encontró Spring Boot 4.1.1, Java 21, Maven, Spring Data JPA y el controlador de PostgreSQL (driver), pero prácticamente no existían frontend, infraestructura, CI/CD ni pruebas de integración reales.

- Conservar Spring Boot, Java 21, Maven y Spring Data JPA.
- Eliminar o dejar de usar Thymeleaf si no forma parte de la solución del frontend.
- Reemplazar el controlador de PostgreSQL (driver) por MySQL Connector/J.
- Configurar la conexión a MySQL (datasource).
- Crear migraciones Flyway para MySQL.
- Inicializar Angular + Ionic.
- Añadir Spring Security, Actuator, Resilience4j y Caffeine.
- Añadir Testcontainers MySQL, WireMock y JaCoCo.
- Añadir Playwright y k6.
- Crear Dockerfiles, la configuración de Swarm, los scripts y los flujos automáticos (workflows) de GitHub Actions.

# 46. Orden recomendado de implementación

> **1.** Estandarizar MySQL 8.4 LTS en Docker y migrar/importar el trabajo existente.
>
> **2.** Actualizar pom.xml: quitar PostgreSQL, añadir MySQL Connector/J y soporte necesario de Flyway para MySQL.
>
> **3.** Crear estructura modular backend + datasource + Flyway.
>
> **4.** Implementar Spring Security.
>
> **5.** Inicializar Angular + Ionic.
>
> **6.** Completar el primer caso de uso de extremo a extremo (end-to-end).
>
> **7.** Crear pruebas de integración con Testcontainers MySQL + JaCoCo desde el primer caso de uso.
>
> **8.** Crear Dockerfiles y un ambiente local que pueda levantarse de la misma forma en distintos computadores.
>
> **9.** Añadir Playwright para los flujos funcionales.
>
> **10.** Montar Swarm en dos computadores y Actuator.
>
> **11.** Ejecutar prueba de Availability.
>
> **12.** Crear k6 y ejecutar pruebas de Performance.
>
> **13.** Añadir WireMock + Resilience4j a las integraciones implementadas.
>
> **14.** Crear GitHub Actions CI.
>
> **15.** Publicar las imágenes en GHCR.
>
> **16.** Configurar el self-hosted runner y el despliegue continuo (CD).
>
> **17.** Finalizar deploy.sh, la prueba rápida de funcionamiento (smoke test), el versionado y el procedimiento de rollback.
>
> **18.** Guardar todas las evidencias y preparar la demostración.

# 47. Evidencias finales para la sustentación

- Casos de uso end-to-end funcionando desde GUI hasta MySQL e integración externa cuando corresponda.
- Reporte de pruebas de integración backend.
- Reporte JaCoCo y quality gate.
- Playwright automático y reportes.
- Dos réplicas backend en Swarm.
- Caída manual de una réplica con tráfico continuo y métricas k6.
- Pruebas k6 de 50 y 100 usuarios con P95, error rate y throughput.
- Pipeline CI/CD completo.
- Imágenes versionadas en GHCR.
- Deploy desde un único script.
- Smoke test automático.
- Evidencia de rollback/versionado.
- Migraciones MySQL reproducibles con Flyway.

# 48. Fuentes oficiales principales

## Backend y Java

- https://docs.spring.io/spring-boot/
- https://docs.spring.io/spring-security/reference/
- https://docs.spring.io/spring-data/jpa/reference/
- https://maven.apache.org/
- https://www.oracle.com/java/technologies/java-se-support-roadmap.html

## MySQL

- https://dev.mysql.com/doc/refman/8.4/en/
- https://www.mysql.com/support/eol-notice.html
- https://dev.mysql.com/doc/connector-j/en/
- https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html
- https://dev.mysql.com/doc/workbench/en/

## Frontend

- https://angular.dev/
- https://ionicframework.com/docs
- https://capacitorjs.com/docs
- https://react.dev/
- https://docs.flutter.dev/platform-integration/web

## Infraestructura

- https://docs.docker.com/engine/
- https://docs.docker.com/engine/swarm/services/
- https://docs.docker.com/engine/swarm/ingress/
- https://kubernetes.io/docs/
- https://docs.podman.io/

## CI/CD

- https://docs.github.com/en/actions
- https://docs.github.com/en/actions/concepts/runners/self-hosted-runners
- https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
- https://www.jenkins.io/doc/book/pipeline/

## Testing

- https://java.testcontainers.org/modules/databases/mysql/
- https://wiremock.org/docs/
- https://www.jacoco.org/jacoco/trunk/doc/maven.html
- https://playwright.dev/docs/ci
- https://grafana.com/docs/k6/latest/

## Availability y Performance

- https://docs.spring.io/spring-boot/reference/actuator/
- https://resilience4j.readme.io/
- https://docs.spring.io/spring-boot/reference/io/caching.html
- https://redis.io/docs/latest/develop/use-cases/cache-aside/

# 49. Conclusión

La arquitectura se seleccionó para maximizar la evidencia que puede demostrarse en la primera entrega sin introducir complejidad que no aporte directamente a los requisitos de calidad prioritarios (ASR). Cada herramienta tiene una alternativa real estudiada, un motivo explícito de selección, una desventaja o costo aceptado (trade-off) y una prueba concreta de validación.

La regla operativa del equipo será: no afirmar que una decisión funciona solo porque la documentación dice que debería hacerlo. La implementaremos, ejecutaremos una prueba reproducible, mediremos el resultado y conservaremos la evidencia.
