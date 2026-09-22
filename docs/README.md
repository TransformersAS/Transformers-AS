# Documentación del proyecto

Este es el punto de entrada a las guías de Marketplace. Los documentos están agrupados por tema y sus nombres están en español, en minúsculas y separados por guiones. **CU** significa **caso de uso**; se conserva su número para relacionar las guías con los requisitos.

## Por dónde empezar

- **Para abrir la aplicación en Windows:** [Iniciar la demo](ejecucion/iniciar-demo-windows.md).
- **Para demostrar los casos CU-08 y CU-11:** [Guía de demostración](ejecucion/guia-demostracion-autenticacion-y-cancelacion.md).
- **Para preparar la exposición y responder preguntas:** [Sustentación de autenticación y cancelación](sustentacion/cu-08-autenticacion-cu-11-cancelacion.md).
- **Para instalar y desarrollar el proyecto completo:** [README principal](../README.md).

## Ejecución y entrega

| Documento | Para qué sirve |
| --- | --- |
| [Iniciar la demo en Windows](ejecucion/iniciar-demo-windows.md) | Abrir el ejecutable, usar las cuentas de demostración y cerrar la aplicación. |
| [Demostrar autenticación y cancelación](ejecucion/guia-demostracion-autenticacion-y-cancelacion.md) | Seguir los pasos manuales de CU-08 y CU-11 y comprobar la evidencia visible. |
| [Construir el ejecutable de Windows](ejecucion/construir-ejecutable-windows.md) | Compilar, empaquetar y validar el launcher y entender su configuración. |

## Desarrollo y arquitectura

| Documento | Para qué sirve |
| --- | --- |
| [Guía del frontend](desarrollo/guia-frontend.md) | Ejecutar y compilar la interfaz Angular/Ionic. |
| [Módulos del backend](arquitectura/modulos-del-backend.md) | Entender las capas, dependencias y reglas para incorporar casos de uso. |
| [Migraciones de base de datos](arquitectura/migraciones-base-de-datos.md) | Consultar las convenciones de Flyway y el contexto inicial del esquema. |

## Seguridad y cuentas — CU-08

| Documento | Para qué sirve |
| --- | --- |
| [Aislamiento entre cuentas de compradores](seguridad/aislamiento-cuentas-compradores.md) | Entender cómo se protegen los recursos de cada comprador y se exige su rol activo. |
| [Permisos vigentes durante la sesión](seguridad/permisos-vigentes-en-sesion.md) | Revisar qué pasa al cambiar los roles o el estado de una cuenta autenticada. |
| [Mantener la sesión iniciada](seguridad/mantener-sesion-iniciada.md) | Entender sesiones persistentes, cookies y vencimientos. |
| [Verificación de correo](seguridad/verificacion-correo.md) | Consultar el envío, reenvío y confirmación del correo de la cuenta. |
| [Recuperación de contraseña por SMTP](seguridad/recuperacion-contrasena-smtp.md) | Consultar la recuperación de acceso mediante correo electrónico. |

## Casos de uso

| Documento | Para qué sirve |
| --- | --- |
| [CU-19: devolución de compra](casos-de-uso/cu-19-devolucion-compra.md) | Consultar el flujo de devoluciones, sus contratos y limitaciones. |
| [CU-20: reportes de contenido](casos-de-uso/cu-20-reportes-contenido.md) | Consultar cómo se radican y consultan reportes de publicaciones. |

## Pruebas

| Documento | Para qué sirve |
| --- | --- |
| [Pruebas de autenticación y sesiones — CU-08](pruebas/cu-08-autenticacion-y-sesiones.md) | Localizar pruebas de backend, interfaz y extremo a extremo, con sus alcances. |
| [Cobertura de integración del backend](pruebas/cobertura-integracion-backend.md) | Ejecutar y auditar las pruebas de integración y su cobertura. |
| [Datos de demostración y rendimiento](pruebas/datos-demostracion-y-rendimiento.md) | Preparar datos reproducibles para demostraciones y pruebas de carga. |

## Despliegue e integración continua

| Documento | Para qué sirve |
| --- | --- |
| [Despliegue con Docker Swarm](despliegue/docker-swarm.md) | Preparar y desplegar el stack, verificar sus servicios y conocer sus límites. |
| [Integración continua en GitHub](despliegue/integracion-continua-github.md) | Consultar la validación automática y publicación de imágenes en GHCR. |

## Sustentación

| Documento | Para qué sirve |
| --- | --- |
| [CU-08: autenticación y CU-11: cancelación](sustentacion/cu-08-autenticacion-cu-11-cancelacion.md) | Consultar trazabilidad, diagramas, referencias al código y preguntas para la exposición. |

## Organización del repositorio

- `backend/`: código Java, pruebas, recursos y migraciones SQL.
- `frontend/`: interfaz Angular/Ionic y pruebas de navegador.
- `launcher/`: código del ejecutable de Windows.
- `scripts/`: herramientas de ejecución, datos y pruebas de carga.
- `.github/workflows/`: automatizaciones de GitHub Actions.
- `docs/`: documentación del proyecto agrupada en las categorías anteriores.

El `README.md` de la raíz mantiene la presentación y las instrucciones generales. `docs/swarm.md` conserva un acceso a la guía nueva para las referencias existentes en scripts y configuraciones.

Para añadir documentación, elegir la carpeta del tema, usar un nombre descriptivo en español sin espacios ni tildes y registrar el enlace en este índice. Los comandos y rutas de código se conservan según el directorio de ejecución indicado en cada guía.
