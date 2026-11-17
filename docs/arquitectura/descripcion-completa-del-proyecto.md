# Descripción completa del proyecto Marketplace — TransformersAS

Fecha de revisión: 16 de noviembre de 2026.

Este documento explica qué contiene el proyecto, cómo funciona, qué herramientas utiliza, para qué sirve cada una y qué necesidad resuelve. Está pensado para entender el sistema y preparar una sustentación sin tener que leer primero todo el código.

El inventario se basa en el código, las dependencias y las configuraciones del repositorio. Cuando no existe una justificación histórica explícita, el apartado «por qué» explica la utilidad técnica que se deduce de la implementación; no atribuye al equipo decisiones que no estén registradas. Las versiones son las declaradas en los archivos del proyecto, no una comprobación de las versiones más recientes disponibles.

## 1. Qué es y para qué sirve

TransformersAS es un marketplace: una plataforma donde compradores consultan productos y realizan compras, mientras los vendedores administran sus tiendas y atienden ventas. También incorpora soporte y administración para gestionar problemas, contenido y restricciones.

El sistema cubre el recorrido comercial desde la exploración del catálogo hasta el seguimiento del pedido y la atención posterior a la compra. Incluye identidad, inventario, pagos, notificaciones y herramientas de operación y evaluación.

### Personas que utilizan el sistema

| Actor | Para qué utiliza la plataforma |
| --- | --- |
| Visitante | Explorar el catálogo, consultar productos y conocer las opciones de registro y acceso. |
| Comprador | Comprar, gestionar direcciones, seguir pedidos, guardar favoritos, crear alertas, escribir reseñas y solicitar atención posterior a la compra. |
| Vendedor | Administrar tienda, publicaciones, existencias, promociones, pedidos y atención a compradores. |
| Soporte | Atender reclamaciones escaladas y moderar reportes de contenido. |
| Administrador | Configurar catálogo, evaluar tiendas y gestionar restricciones de cuentas. |
| Operador o evaluador | Iniciar el entorno, preparar demostraciones y revisar evidencias de funcionamiento y calidad. |

Una misma cuenta puede tener varios roles. El rol activo determina el contexto de trabajo y las operaciones autorizadas.

## 2. Funcionalidades del proyecto

El siguiente inventario describe capacidades representadas en código y documentación. Su presencia no certifica que todas hayan sido probadas en el entorno que se esté ejecutando.

| Área | Qué incluye y para qué sirve |
| --- | --- |
| Catálogo | Consulta de productos, categorías, marcas y atributos; permite encontrar y entender la oferta comercial. |
| Publicaciones | Gestión de productos del vendedor, imágenes y datos comerciales. |
| Comparación y recomendaciones | Apoyo a la elección de productos y recomendaciones a partir del catálogo y las interacciones. Existe un cliente de Gemini cuya ejecución depende de configuración. |
| Registro y cuentas | Registro de compradores y vendedores, perfil y preferencias. |
| Autenticación | Inicio y cierre de sesión, cambio de rol activo, sesiones persistentes y consulta de sesiones activas. |
| Recuperación y verificación | Verificación de correo y recuperación de contraseña mediante mecanismos de token y transporte SMTP configurado. |
| Direcciones | Administración de direcciones de entrega y selección durante la compra. |
| Carrito y checkout | Selección de productos, cantidades y preparación de la compra con entrega y pago. |
| Inventario y reservas | Control de existencias y reservas temporales; evita tratar como disponible una unidad comprometida por otra compra. |
| Pagos | Integración con Mercado Pago y alternativas de simulación para demostraciones; procesamiento de resultados y notificaciones del proveedor. |
| Pedidos | Consulta, detalle, estados, comprobantes y cancelación según las reglas de negocio. |
| Atención de ventas | Pedidos recibidos y operaciones del vendedor para preparar y despachar ventas. |
| Logística | Métodos de envío, seguimiento, eventos del proveedor y seguimiento de devoluciones; existen modalidades configurables y simuladas. |
| Favoritos y alertas | Guardar productos y recibir avisos cuando se cumplan condiciones, como cambios de precio o disponibilidad. |
| Notificaciones | Bandeja interna persistente, entrega externa y canal Web Push opcional. |
| Reseñas | Valoraciones y comentarios sobre productos comprados, sujetos a reglas de elegibilidad y contenido. |
| Devoluciones | Solicitudes del comprador, atención del vendedor, seguimiento y acciones de reembolso conforme al flujo implementado. |
| Reclamaciones | Comunicación y resolución de problemas de compra, con escalamiento a soporte. |
| Mensajería | Conversaciones y evidencias asociadas a la comunicación entre participantes. |
| Reportes de contenido | Radicación y consulta de reportes, evidencias y moderación por soporte. |
| Restricciones | Aplicación, modificación y retiro de restricciones de acceso a cuentas. |
| Tiendas y suspensiones | Perfil e imágenes de tienda, consulta de estado y evaluación administrativa de antecedentes. |
| Promociones | Gestión de promociones y cupones del vendedor. |
| Cargas masivas | Uso de plantillas Excel para operaciones de inventario y productos previstas por el módulo. |
| Analítica | Consulta de indicadores de ventas para el vendedor. |
| Auditoría | Componentes para conservar trazabilidad de operaciones relevantes. |

Las guías específicas de CU-04, CU-05, CU-06, CU-19, CU-20 y CU-22 están en [casos de uso](../README.md#casos-de-uso). Los documentos de pruebas y sustentación amplían otros recorridos.

## 3. Cómo está construido

La aplicación tiene una interfaz web, un backend y una base de datos. El backend está organizado por módulos de negocio y se despliega como una aplicación Java. Es una organización modular dentro del mismo backend; los paquetes no constituyen microservicios independientes.

```text
Persona usuaria
      |
      v
Navegador: Angular + Ionic
      |
      v
Nginx: entrega la interfaz y dirige /api al backend
      |
      v
Backend: Java + Spring Boot
      |-- MySQL: información comercial, sesiones y trabajos pendientes
      |-- Mercado Pago: pagos, si está configurado
      |-- SMTP: correos, si está configurado
      |-- Proveedor logístico: integración según el modo seleccionado
      |-- Gemini: recomendaciones, si está configurado
      `-- RabbitMQ + Web Push: notificaciones, con el complemento activo
```

### Separación de responsabilidades

| Parte | Qué hace | Por qué es útil |
| --- | --- | --- |
| Frontend | Presenta pantallas, formularios, navegación y mensajes. | Hace posible que las personas utilicen las funciones del sistema. |
| API HTTP y JSON | Transporta solicitudes y respuestas entre interfaz y servidor. | Define contratos claros para intercambiar información. |
| Backend | Valida permisos, ejecuta reglas y coordina operaciones. | Centraliza decisiones que deben ser confiables, como quién puede cancelar un pedido. |
| Base de datos | Conserva información y relaciones de forma persistente. | Mantiene cuentas, compras y estados aunque se reinicien los procesos. |
| Integraciones | Conectan pagos, correo, logística y otros servicios. | Permiten delegar funciones especializadas a proveedores. |
| Infraestructura | Construye, ejecuta, supervisa y distribuye los componentes. | Facilita reproducir el entorno y detectar fallos. |

### Capas del backend

La [guía de módulos](modulos-del-backend.md) establece una separación de dominio, aplicación e infraestructura. No todos los paquetes históricos tienen idéntica estructura.

- **Dominio:** modelos, reglas e invariantes del negocio y contratos de repositorio.
- **Aplicación:** casos de uso que coordinan una operación y sus datos de entrada y salida.
- **Infraestructura:** controladores HTTP, persistencia e integraciones externas.
- **DTO y mappers:** transportan datos y convierten modelos sin exponer directamente las entidades de persistencia.
- **Shared:** configuración técnica y contratos transversales.

Esta separación facilita modificar una integración o una pantalla sin mezclar sus detalles con las reglas comerciales. El repositorio no incorpora Spring Modulith; el aislamiento entre módulos depende de las reglas y del código.

## 4. Herramientas de la interfaz

| Herramienta | Para qué se usa | Por qué resulta útil en este proyecto |
| --- | --- | --- |
| Angular 20.3.35 | Framework principal de la interfaz. | Organiza componentes, formularios y consumo de la API en una aplicación con numerosas funciones y roles. |
| Ionic Angular 8.8.19 | Componentes de interfaz y adaptación a distintos tamaños de pantalla. | Proporciona controles visuales reutilizables para formularios, diálogos y navegación. |
| TypeScript 5.9.x | Lenguaje del frontend. | Los tipos ayudan a detectar errores y a mantener contratos de datos. |
| HTML y SCSS | Estructura y presentación de las pantallas. | Separan el contenido visual de su estilo y permiten reutilizar reglas de diseño. |
| RxJS 7.8.x | Manejo de flujos asíncronos y respuestas HTTP. | Facilita coordinar datos que llegan después de una acción del usuario. |
| HttpClient e interceptores | Solicitudes al backend y tratamiento transversal de sesión. | Evitan repetir en cada pantalla la lógica común de comunicación. |
| Formularios Angular | Captura y validación de entradas. | Permiten presentar errores cerca de los campos y controlar el estado del formulario. |
| Angular Service Worker y SwPush | Soporte del trabajador en segundo plano y suscripciones push. | Permiten integrar notificaciones del navegador en el build de producción. No implican que todos los procesos comerciales funcionen sin conexión. |
| Ionicons | Iconos de la interfaz. | Aporta símbolos consistentes para acciones habituales. |
| Capacitor 7.6.10 | Dependencia de integración con plataformas nativas. | Ofrece una base para integrar capacidades de dispositivo; su presencia no demuestra que exista una aplicación Android o iOS publicada. |
| Node.js y npm | Instalación de dependencias, compilación y ejecución de herramientas del frontend. | Permiten construir la interfaz de forma reproducible mediante el archivo de bloqueo y `npm ci`. La imagen de construcción usa Node 22. |
| Angular CLI y build-angular | Comandos de desarrollo y generación del build. | Automatizan la transformación del código fuente en archivos listos para servir. |
| tslib y zone.js | Soporte de ejecución de TypeScript y Angular. | Cubren necesidades técnicas del framework; no son funcionalidades comerciales. |

Las versiones y scripts se consultan en [package.json](../../frontend/package.json). La aplicación arranca con componentes standalone en [main.ts](../../frontend/src/main.ts).

## 5. Herramientas del backend

| Herramienta | Para qué se usa | Por qué resulta útil en este proyecto |
| --- | --- | --- |
| Java 21 | Lenguaje y plataforma del servidor. | Permite implementar reglas, transacciones e integraciones dentro del ecosistema Spring. |
| Spring Boot 4.1.1 | Arranque y configuración de la aplicación. | Integra las piezas del servidor y reduce configuración repetitiva. |
| Spring Web MVC | Controladores y contratos HTTP. | Expone operaciones que consume la interfaz. |
| Spring Security | Autenticación, autorización y protecciones web. | Aplica reglas de acceso desde el servidor para compradores, vendedores, soporte y administración. |
| Spring Session JDBC | Persistencia de sesiones en la base de datos. | Evita que la sesión dependa únicamente de la memoria de un proceso; permite compartir su almacenamiento entre réplicas. |
| Bean Validation | Validación de datos de entrada. | Rechaza datos incompletos o inválidos antes de continuar operaciones. |
| Spring Data JPA e Hibernate | Mapeo y acceso a información relacional. | Reducen código repetitivo de persistencia. Hibernate valida el esquema existente. |
| JDBC y JdbcTemplate | Consultas y actualizaciones SQL específicas. | Permiten control explícito en operaciones como trabajos pendientes o reclamación de tareas. |
| Spring Mail | Envío de correos por SMTP. | Reutiliza un transporte configurable para verificación y recuperación de acceso. |
| Spring AMQP | Publicación y consumo de mensajes RabbitMQ. | Integra la cola opcional con la aplicación Spring. |
| Resilience4j 2.4.0 | Circuit breakers en integraciones HTTP de logística y notificaciones. | Evita insistir temporalmente contra un proveedor que está fallando. No reemplaza los reintentos propios de los flujos de negocio. |
| Spring AspectJ | Soporte para funcionalidades transversales basadas en aspectos. | Habilita infraestructura requerida por componentes que aplican comportamiento alrededor de operaciones. |
| Spring Cache y Caffeine | Infraestructura de caché declarada. | Ofrece una base para almacenar resultados temporalmente. `CacheConfiguration` indica que aún no define cachés ni políticas de negocio; no se debe atribuir una mejora medida a su sola presencia. |
| Lombok | Reducción de código repetitivo en clases Java. | Permite generar elementos auxiliares durante compilación cuando se utiliza. |
| Maven y Maven Wrapper | Dependencias, compilación, pruebas y empaquetado. | El Wrapper facilita usar la versión de construcción prevista sin instalar Maven por separado. |

La fuente principal de versiones y dependencias es [pom.xml](../../backend/demo/pom.xml).

## 6. Base de datos y persistencia

| Componente | Para qué se usa | Por qué se usa aquí |
| --- | --- | --- |
| MySQL 8.4.11 LTS | Base de datos relacional principal. | El negocio contiene relaciones y operaciones que deben mantenerse consistentes: usuarios, productos, pedidos, pagos y existencias. |
| MySQL Connector/J | Conexión JDBC entre Java y MySQL. | Permite que el backend utilice el motor de base de datos. |
| Flyway | Migraciones SQL versionadas. | Permite evolucionar el esquema de manera ordenada y reproducible. |
| Transacciones y bloqueos | Coordinación de cambios relacionados. | Evitan estados parciales y conflictos en operaciones sensibles, como reservar inventario o reclamar trabajos pendientes. |
| Volúmenes Docker | Persistencia de los archivos de MySQL. | Conservan la información al detener o recrear contenedores sin eliminar el volumen. |

Flyway modifica el esquema y Hibernate lo valida. Las reglas del proyecto prohíben utilizar la generación automática de Hibernate como sustituto de las migraciones y recomiendan añadir versiones nuevas sin editar migraciones aplicadas.

Los datos incluyen cuentas y sesiones, catálogo y tiendas, carrito y direcciones, reservas y pedidos, pagos, reseñas, devoluciones, mensajes, reportes y notificaciones. La estructura exacta está en [las migraciones](../../backend/demo/src/main/resources/db/migration).

## 7. Servicios externos y archivos generados

| Herramienta o integración | Para qué sirve | Por qué se utiliza y qué necesita |
| --- | --- | --- |
| Mercado Pago | Procesar pagos y consultar sus resultados. | Delega el cobro a una pasarela especializada. Requiere credenciales y configuración de retorno y webhook; las pruebas sandbox y el simulador son escenarios diferentes. |
| Webhooks | Recibir eventos de pagos o logística. | Permiten enterarse de cambios del proveedor sin depender de que el usuario mantenga una pantalla abierta. Deben validarse conforme a los controles de cada integración. |
| SMTP | Transportar correos electrónicos. | Permite enviar mensajes de verificación y recuperación con un servidor de correo configurado. |
| Gemini | Cliente de recomendaciones sobre productos candidatos e interacciones. | Añade una integración de IA para apoyar recomendaciones. Requiere clave y modelo configurados; la existencia del cliente no acredita disponibilidad del proveedor. |
| Proveedor logístico HTTP | Integración con servicios de envío. | Separa las operaciones logísticas del resto de las reglas. El proyecto también dispone de modalidades simuladas para demostrar flujos. |
| Apache PDFBox 3.0.8 | Generar comprobantes PDF. | Permite producir documentos desde Java sin necesitar un motor HTML ni un servicio externo para esa generación. |
| Apache POI 5.4.1 | Leer y escribir plantillas Excel `.xlsx`. | Facilita las cargas masivas del vendedor en lugar de capturar cada fila manualmente. |
| TwelveMonkeys ImageIO WebP 3.12.0 | Soporte de procesamiento de imágenes WebP. | Amplía los formatos que puede manejar el procesamiento de imágenes de Java. |
| web-push 5.1.2 | Cifrar y firmar mensajes Web Push. | Implementa el envío compatible con los servicios push de los navegadores. |
| Bouncy Castle y jose4j | Operaciones criptográficas usadas por la infraestructura push. | Aportan soporte para claves, firma y protocolos criptográficos. |

### Ejemplo: recorrido de una compra

1. El comprador explora el catálogo y añade productos al carrito.
2. El checkout recibe las selecciones y la dirección de entrega.
3. El backend valida la operación y gestiona las reservas de existencias.
4. Se inicia el pago con el proveedor o con el modo de demostración seleccionado.
5. El backend procesa y verifica el resultado según el flujo de pagos; el regreso del navegador no debe confundirse por sí solo con una confirmación financiera.
6. El pedido continúa a la atención del vendedor y al seguimiento logístico.
7. Según el estado y las reglas, aparecen acciones como consultar comprobante, cancelar, reseñar o solicitar devolución.

El doble cotejo financiero y las protecciones de confirmación se explican en [safety de pagos](safety-pagos-marketplace.md).

## 8. RabbitMQ y notificaciones

La bandeja interna se guarda en MySQL. RabbitMQ y Web Push se habilitan mediante [compose.notificaciones.yaml](../../compose.notificaciones.yaml); no forman parte obligatoria del arranque base.

| Pieza | Para qué sirve | Por qué es necesaria en este flujo |
| --- | --- | --- |
| Notificación interna | Conservar el aviso dentro del marketplace. | El usuario puede consultarlo aunque no acepte notificaciones del navegador. |
| Outbox en `notification_jobs` | Registrar el trabajo pendiente en la misma transacción del aviso. | Evita perder el compromiso de envío si el proceso cae después de guardar la notificación. |
| Publicador con confirmación | Enviar a RabbitMQ y esperar su aceptación. | Permite conservar y recuperar eventos no confirmados. |
| RabbitMQ y cola durable | Desacoplar la creación del aviso de su envío externo. | Permite procesar trabajos pendientes y soportar interrupciones temporales. |
| Consumidor | Reclamar y ejecutar el trabajo. | Coordina la entrega y registra el resultado. |
| Claves VAPID | Identificar y firmar al emisor de Web Push. | Permiten autenticar el envío sin requerir una cuenta de Firebase para generar las claves. |
| Service worker | Recibir el push en el navegador. | Puede mostrar avisos según los permisos y condiciones del navegador y del sistema operativo. |

Sin el complemento se conserva el flujo con ejecutor en memoria. Con la cola activa, el consumidor sustituye ese ejecutor para el envío externo. La entrega utiliza recuperación y controles de concurrencia; no debe describirse como garantía universal de entrega exactamente una vez.

Web Push necesita permiso del usuario, suscripción registrada y un contexto permitido por el navegador, normalmente HTTPS o localhost. La recepción no está garantizada si el navegador está completamente detenido. Más detalles en [Web Push y RabbitMQ](../ejecucion/notificaciones-push-rabbitmq.md).

## 9. Seguridad

| Mecanismo | Para qué sirve | Qué problema aborda |
| --- | --- | --- |
| BCrypt con coste 12 | Almacenar hashes de contraseña. | Evita conservar contraseñas en texto plano y encarece la comprobación masiva de candidatos. |
| Sesiones y cookies | Identificar al usuario autenticado entre solicitudes. | Mantiene la continuidad del acceso y permite gestionar sesiones desde el servidor. |
| Rol activo y permisos | Autorizar operaciones. | Evita que una cuenta ejecute acciones ajenas al rol con el que está trabajando. |
| Comprobación de propiedad | Restringir acceso a recursos propios. | Protege pedidos, direcciones y otros datos frente a accesos entre cuentas. |
| CSRF | Validar solicitudes que utilizan una sesión del navegador. | Reduce el riesgo de acciones inducidas desde otro sitio. |
| Control de intentos de acceso | Limitar intentos de autenticación. | Reduce abuso y ataques repetidos al login. |
| Tokens de verificación y recuperación | Confirmar correo y permitir recuperar acceso. | Separa estos procesos del conocimiento de la contraseña actual. |
| Cabeceras de seguridad de Nginx | Aplicar CSP, protección de contenido y restricciones de incrustación. | Reduce superficies de ejecución e inclusión de contenido no previstas. |
| Nonces en HTML | Autorizar scripts y estilos de la respuesta bajo la CSP. | Permite compatibilizar Angular/Ionic con una política de contenido restrictiva. |
| Configuración de proxies confiables | Determinar qué origen e IP se aceptan como válidos. | Evita confiar indiscriminadamente en cabeceras enviadas por un cliente. |
| Variables de entorno y secrets | Separar credenciales del código. | Facilita configurar cada entorno sin publicar claves en el repositorio. |

El proyecto utiliza sesiones JDBC; no debe explicarse su autenticación principal como un sistema basado en JWT. Los archivos `.env` contienen configuración sensible y no forman parte de este inventario de valores.

## 10. Ejecución, despliegue y entrega

| Herramienta | Para qué sirve | Por qué se utiliza |
| --- | --- | --- |
| Docker | Empaquetar frontend, backend y servicios en imágenes. | Reduce diferencias entre equipos y evita instalar toda la cadena de ejecución en el host. |
| Docker Desktop | Ejecutar el motor y contenedores en el equipo de demostración. | Hace viable el arranque local en Windows con contenedores Linux. |
| Docker Compose | Coordinar servicios, redes, puertos, volúmenes y comprobaciones de salud. | Permite levantar el entorno local con una configuración conjunta. |
| Archivos Compose complementarios | Adaptar escenarios de demo, pruebas, seguridad, rendimiento, notificaciones y túnel. | Permiten cambiar un escenario sin reemplazar toda la configuración base. |
| Nginx | Servir los archivos compilados y actuar como proxy hacia la API. | Ofrece una entrada web común y configuración de cabeceras, resolución de destinos y tratamiento de fallos de conexión. |
| Construcción por etapas | Separar herramientas de compilación de la imagen final. | La imagen del frontend sirve archivos con Nginx sin necesitar Node para atender a cada usuario. |
| Docker Swarm | Desplegar servicios con réplicas y actualizaciones del stack. | Permite evaluar un escenario distribuido diferente al Compose local. |
| GHCR | Almacenar imágenes publicadas del backend y frontend. | Permite desplegar una imagen construida y etiquetada por la automatización. |
| .NET 8 y C# | Construir el launcher `Marketplace.exe`. | Ofrece un iniciador Windows que prepara configuración, coordina Docker y abre el navegador. No sustituye al backend Java. |
| PowerShell, Bash, Python y scripts Node | Automatizar arranque, preparación de datos, demostraciones y comprobaciones. | Evitan repetir manualmente secuencias de tareas técnicas. |

### Entornos que conviene distinguir

- **Desarrollo local:** código y servicios levantados manualmente o mediante Compose.
- **Entrega Windows:** launcher y configuración de demostración; requiere el proyecto completo y Docker Desktop. El ejecutable autocontenido incluye .NET.
- **Demostraciones y pruebas:** perfiles y complementos con datos y condiciones controladas, incluidos pagos y logística simulados cuando corresponda.
- **Swarm:** escenario de despliegue con réplicas definido en `stack.yml` y herramientas de despliegue.

El stack declara dos réplicas de frontend y backend y una sola de MySQL. Esto no proporciona alta disponibilidad de la base de datos. Una ejecución en un solo computador tampoco demuestra tolerancia a la pérdida de un nodo.

Los puertos locales habituales son 4300 para la interfaz, 8080 para el backend y 3307 para acceder a MySQL desde el host. Dentro de la red de contenedores, MySQL escucha en 3306. Los puertos pueden variar mediante configuración.

## 11. Pruebas y herramientas de calidad

| Herramienta o técnica | Para qué sirve | Por qué se utiliza |
| --- | --- | --- |
| JUnit Jupiter y herramientas de prueba Spring | Ejecutar pruebas de reglas, web y persistencia. | Comprueban resultados esperados y regresiones en el backend. |
| Spring Security Test | Probar acceso autenticado y permisos. | Permite verificar que la seguridad forma parte del comportamiento probado. |
| Testcontainers con MySQL | Crear una base temporal real para pruebas. | Reduce diferencias frente al motor utilizado por la aplicación. Requiere Docker. |
| WireMock 3.13.2 | Simular servicios HTTP externos. | Permite probar respuestas, errores y tiempos sin depender de proveedores reales. |
| GreenMail 2.1.3 | Servidor de correo para pruebas. | Permite inspeccionar correos sin enviarlos a destinatarios reales. |
| Awaitility | Esperar condiciones en pruebas asíncronas. | Evita depender de pausas fijas arbitrarias. |
| Maven Surefire | Ejecutar y reportar pruebas Java. | Integra la verificación en el proceso de construcción. |
| JaCoCo 0.8.14 | Medir cobertura de código ejecutado por las pruebas. | Permite aplicar umbrales y localizar líneas no recorridas. Cobertura no equivale a ausencia de errores. |
| Playwright | Automatizar recorridos en navegador. | Verifica interacción entre pantallas y backend y apoya demostraciones reproducibles. |
| k6 | Generar carga y medir tiempos y errores. | Permite contrastar objetivos de desempeño con escenarios y cantidades de usuarios definidos. |
| OWASP ZAP | Analizar seguridad web. | Ayuda a detectar problemas observables desde la aplicación HTTP. No sustituye revisión de autorización ni de reglas comerciales. |
| Auditoría de dependencias | Revisar riesgos de paquetes utilizados. | Ayuda a mantener las herramientas y bibliotecas frente a vulnerabilidades conocidas. |
| Evaluación de usabilidad | Inspección, tareas, encuestas y registro de hallazgos. | Permite evaluar si las personas entienden y completan los recorridos. |

La configuración habitual del backend exige al menos 95 % de cobertura de líneas. Existen perfiles de cobertura específicos; sus resultados solo son comparables dentro del alcance que declaran. Este documento no ejecuta ni certifica la suite.

La evaluación global de usabilidad documenta una inspección y un protocolo preparado; no acredita sesiones ya ejecutadas con participantes. Los criterios y formatos se encuentran en [la guía RNF-025 a RNF-030](../pruebas/usabilidad/guia-rnf-025-a-030.md).

## 12. Monitoreo e integración continua

| Herramienta | Qué hace | Por qué se utiliza |
| --- | --- | --- |
| Spring Boot Actuator | Expone salud, liveness y readiness. | Permite distinguir si el proceso vive y si está preparado para atender según sus dependencias. |
| Micrometer | Instrumentación y exportación de métricas. | Conecta las mediciones del backend con herramientas de monitoreo. |
| Prometheus | Recopila métricas expuestas por la aplicación. | Conserva series temporales para observar comportamiento y carga. |
| Grafana | Presenta tableros de métricas. | Facilita interpretar indicadores operativos. |
| Git y GitHub | Versiones del código y colaboración mediante ramas. | Conservan historial y permiten revisar e integrar cambios. |
| GitHub Actions | Automatiza verificaciones y construcción. | Ejecuta controles repetibles y conserva reportes y artefactos. |
| Docker Buildx | Construcción de imágenes en la automatización. | Apoya la publicación para las arquitecturas configuradas. |

Prometheus y Grafana tienen configuración en `monitoring/` y un stack complementario [stack.monitoreo.yml](../../stack.monitoreo.yml). No debe asumirse que estén activos con el Compose base. El endpoint de métricas contempla protección mediante configuración de acceso.

Los workflows están en [.github/workflows](../../.github/workflows): CI del backend y las imágenes, construcción del launcher y análisis ZAP. La CI construye y valida; en `main` publica imágenes en GHCR con etiquetas `latest` y `sha-<commit>`. Esto no implica un despliegue remoto automático.

## 13. Organización del repositorio

| Ruta | Qué contiene y para qué sirve |
| --- | --- |
| `frontend/` | Interfaz, recursos visuales, configuración de compilación, Nginx y pruebas de navegador. |
| `backend/demo/` | Aplicación Java, dependencias Maven, pruebas, configuración y recursos. El nombre de carpeta no significa que todo el backend sea simulado. |
| `backend/demo/src/main/java/com/transformersas/marketplace/` | Módulos funcionales del servidor. |
| `backend/demo/src/main/resources/db/migration/` | Historial SQL de la estructura de datos. |
| `backend/demo/src/main/resources/demo-data/` | Datos preparados para demostraciones. |
| `launcher/Marketplace/` | Código C# del iniciador Windows. |
| `scripts/` | Arranque, carga de datos, demos, mediciones y validaciones automatizadas. |
| `monitoring/` | Configuración de Prometheus y Grafana. |
| `validation/` | Evidencias y configuraciones de validaciones específicas. |
| `.github/workflows/` | Automatización de construcción y controles. |
| `docs/` | Arquitectura, casos de uso, ejecución, seguridad, pruebas y sustentación. |
| `compose.yaml` y `compose.*.yaml` | Entorno base y complementos para escenarios concretos. |
| `stack.yml` y `stack.monitoreo.yml` | Despliegue Swarm y complemento de monitoreo. |
| `.env.example` | Referencia de variables configurables, sin utilizarla como sustituto de secretos propios. |

### Módulos del backend presentes en el código

| Paquetes | Responsabilidad principal |
| --- | --- |
| `auth`, `users`, `sellers` | Identidad, perfiles, sesiones y registro de vendedores. |
| `catalog`, `product` | Estructura del catálogo y publicaciones. |
| `stores`, `suspensions` | Tiendas, estado y evaluación. |
| `inventory`, `stock`, `reservation` | Existencias, gestión del vendedor y reservas. |
| `cart`, `checkout` | Carrito y preparación de compra. |
| `orders`, `payments`, `logistics` | Pedidos, pagos y transporte. |
| `address` | Direcciones de entrega. |
| `favorites`, `reviews`, `recommendation` | Seguimiento, valoraciones y recomendaciones. |
| `notifications`, `messaging` | Avisos y conversaciones. |
| `returns`, `claims` | Devoluciones y reclamaciones. |
| `reports`, `restrictions` | Moderación de contenido y restricciones de cuentas. |
| `promotions`, `analytics` | Promociones e indicadores del vendedor. |
| `audit`, `demo`, `shared` | Auditoría, preparación de datos y soporte transversal. |

Algunas responsabilidades aparecen en más de un paquete por la evolución de la implementación. Esta lista describe los directorios actuales y no supone una uniformidad de capas que el código no garantiza.

## 14. Qué está disponible y qué depende del entorno

| Elemento | Cómo interpretarlo |
| --- | --- |
| Código de una función | Prueba que hay implementación; no confirma que todas sus variantes estén validadas en ejecución. |
| Credenciales de proveedor | Son necesarias para las integraciones que las requieren; no se incluyen aquí. |
| Datos demo | Facilitan mostrar recorridos y roles; no son información real de clientes. |
| Simuladores | Permiten observar estados controlados sin una transacción o un envío real. |
| RabbitMQ y Web Push | Requieren activar el complemento y configurar claves, suscripción y permisos. |
| Dependencias de caché y Capacitor | Son infraestructura disponible; no prueban una caché comercial activa ni una app móvil distribuida. |
| Réplicas y monitoreo | Dependen del stack utilizado; no se obtienen automáticamente al abrir la demo Windows. |
| Reportes de pruebas | Deben leerse con fecha, entorno, datos y alcance; una meta configurada no equivale a un resultado obtenido. |

## 15. Guion breve para explicar el proyecto

«TransformersAS es un marketplace con funciones para compradores, vendedores, soporte y administradores. Su interfaz está construida con Angular e Ionic; el servidor usa Java y Spring Boot y conserva los datos en MySQL. Flyway controla los cambios de la base de datos. La seguridad utiliza sesiones persistentes, roles y validaciones de acceso. El sistema integra pagos con Mercado Pago y dispone de correo, logística, recomendaciones y notificaciones según la configuración. Docker permite reproducir el entorno; Compose facilita el uso local y Swarm representa el escenario distribuido. Las pruebas combinan verificación Java, base de datos temporal, navegador, carga y seguridad. GitHub Actions automatiza los controles y la construcción de artefactos».

Para ampliar cada tema, consultar el [índice de documentación](../README.md), el [README principal](../../README.md), la [guía de frontend](../desarrollo/guia-frontend.md), las [migraciones](migraciones-base-de-datos.md) y la [guía de despliegue Swarm](../despliegue/docker-swarm.md).
