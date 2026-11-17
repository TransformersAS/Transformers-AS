# Guía de evaluación y sustentación: RNF-025 a RNF-030

Preparada el 16/11/2026 a partir de las filas del Excel compartidas por la
responsable del atributo. Alcance: Marketplace completo, no solo CU-01 a CU-06.
**Estado: protocolo y plantillas. No contiene resultados nuevos ni declara
cumplimiento.** Completar con evidencias obtenidas en la versión evaluada.

## 1. Qué entregar

Un informe por atributo, con seis apartados (uno por RNF), una matriz de
resultados, hallazgos y enlaces a evidencias. Usar como base:

- [Evaluación del proyecto completo](../usabilidad-proyecto-completo.md): módulos,
  tareas por rol y mapas de navegación.
- [Arquitectura de información](../../arquitectura/usabilidad-arquitectura-informacion.md):
  organización y responsabilidades por contenedor C4.
- [Sesiones](sesiones-usabilidad.csv), [campos](campos-usabilidad.csv) y
  [hallazgos](hallazgos-usabilidad.csv): registros existentes que deben completarse.

La arquitectura explica las decisiones; las pruebas muestran si funcionan para
el usuario. No presentar los contenedores C4 como si fueran pantallas. El frontend
organiza navegación y mensajes; el backend valida y devuelve respuestas
comprensibles; la base conserva los datos y la cola permite gestionar avisos sin
bloquear el flujo. Contrastar estas responsabilidades con la implementación y el
diagrama vigentes, sin afirmar que todo contenedor tiene interfaz propia.

## 2. Preparar la evaluación

1. Registrar fecha, commit (`git rev-parse HEAD`), entorno, navegadores y versiones.
2. Inventariar módulos de visitante, comprador, vendedor, administrador y soporte.
   Incluir formularios y acciones principales, aunque los haya construido otro integrante.
3. Preparar cuentas y datos de prueba: producto publicable, compra sandbox, pedido
   consultable y pedido elegible para reclamación. No utilizar dinero real.
4. Convocar cinco personas externas al desarrollo inmediato, identificadas como
   E01–E05; registrar su perfil sin publicar nombres, documentos ni credenciales.
5. Preparar capturas o grabaciones con consentimiento. Ocultar contraseñas,
   tokens, direcciones reales y datos personales.
6. Hacer una prueba técnica previa. Un problema de entorno debe registrarse;
   no convertir una tarea bloqueada en aprobada.

## 3. RNF-025: completar los flujos sin ayuda directa

**Criterio:** al menos 4 de 5 evaluadores completan una compra, publican un
producto, consultan un pedido y registran una reclamación sin ayuda directa.

Dar las mismas consignas a las cinco personas, sin nombrar botones ni indicar
el camino. Entregar acceso y datos preparados; explicar el contexto sandbox,
pero no resolver la navegación. El facilitador puede leer la consigna o aclarar
el objetivo, no señalar dónde hacer clic. Registrar cualquier ayuda directa.

| Tarea | Consigna para leer | Resultado observable |
| --- | --- | --- |
| T01 compra | Compra este producto con los datos de prueba disponibles e identifica el resultado. | Compra de prueba completada y resultado entendido. |
| T02 publicación | Publica este producto utilizando la información y foto proporcionadas. | Publicación guardada y localizable en el estado esperado. |
| T03 pedido | Encuentra este pedido y dime en qué estado está. | Identifica el pedido y su estado correctos. |
| T04 reclamación | Registra un problema para esta compra y encuentra la solicitud creada. | Reclamación registrada y localizable. |

Cada persona debe disponer de los roles y datos necesarios para las cuatro
tareas. Cambiar al rol vendedor para publicar forma parte del recorrido que se
debe observar. Si no puede ejecutarlo, registrar el bloqueo y su causa.

Completar esta tabla con **S** (éxito sin ayuda), **A** (con ayuda), **N** (no
completada) o **B** (bloqueada). No completar con datos de ejemplo.

| Evaluador | T01 | T02 | T03 | T04 | Completó las cuatro sin ayuda |
| --- | --- | --- | --- | --- | --- |
| E01 | Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |
| E02 | Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |
| E03 | Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |
| E04 | Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |
| E05 | Pendiente | Pendiente | Pendiente | Pendiente | Pendiente |

Para la lectura conjunta del criterio, contar personas con **S en las cuatro
tareas**: deben ser al menos 4/5. Presentar también el éxito por tarea para no
ocultar dificultades. Registrar duración como dato adicional, sin inventar un
tiempo máximo que el requisito no establece. Las demos Playwright no sustituyen
estas sesiones. Si ya hubo sesiones informales sin este registro, recuperar
solo lo comprobable y repetir las tareas cuya evidencia falte.

## 4. RNF-026: percepción de claridad y facilidad

**Criterio:** promedio igual o superior a 4 sobre 5 después de los flujos.

Al terminar, pedir individualmente y sin sugerir respuestas:

1. «En general, ¿qué tan clara y fácil de utilizar te resultó la aplicación?»
   Escala: 1 muy difícil/confusa, 2 difícil, 3 intermedia, 4 fácil/clara, 5 muy fácil/clara.
2. «¿Qué fue lo más difícil de encontrar o entender?» (respuesta abierta).
3. «¿Qué cambiarías?» (respuesta abierta).

La primera pregunta será la medida principal, declarada antes de empezar;
las abiertas sirven para explicar mejoras, no se suman al promedio.

| Evaluador | Puntuación 1–5 | Dificultad observada | Evidencia |
| --- | --- | --- | --- |
| E01 | Pendiente | Pendiente | Pendiente |
| E02 | Pendiente | Pendiente | Pendiente |
| E03 | Pendiente | Pendiente | Pendiente |
| E04 | Pendiente | Pendiente | Pendiente |
| E05 | Pendiente | Pendiente | Pendiente |

Promedio = suma de las cinco puntuaciones / 5. No sustituir por mediana ni
descartar puntuaciones bajas. Si falta alguna respuesta, reportar la muestra
incompleta sin declarar cumplido el criterio previsto para cinco evaluadores.

## 5. RNF-027: mensajes por campo y conservación de datos

**Criterio:** el 100 % de campos inválidos muestra mensaje específico junto al
campo y conserva los demás datos válidos cuando sea posible.

1. Inventariar los formularios principales de todos los módulos y roles.
2. Por cada campo, identificar reglas: obligatorio, formato, longitud y rango.
3. Probar cada regla por separado con el resto de campos válidos.
4. Guardar y comprobar mensaje junto al campo, descripción útil de la corrección
   y conservación de los datos válidos. Probar validación local y del servidor.
5. Corregir el dato y comprobar que se puede continuar.

Registrar en [campos-usabilidad.csv](campos-usabilidad.csv). Si un formulario no
existe o está inaccesible, queda pendiente, no aprobado. Documentar excepciones
justificadas de conservación, especialmente secretos y archivos.

Indicador = casos de campo/regla con todos los controles correctos / casos
evaluados × 100. Publicar además inventariados, evaluados, fallidos y pendientes.
Un 100 % de una muestra pequeña no demuestra el 100 % del inventario completo.

## 6. RNF-028: escritorio entre 1024 y 1920 píxeles

**Criterio:** sin desplazamiento horizontal general ni pérdida de funciones
principales en ese intervalo de ancho.

Evaluar al menos 1024×768, 1280×800, 1366×768, 1440×900 y 1920×1080, zoom 100 %.
Son puntos de muestreo propuestos; arrastrar también el ancho entre ellos para
detectar rupturas. Incluir pantallas y paneles principales de cada rol, formularios,
mensajes de error, tablas y checkout con muchos datos.

Comprobar que botones y textos no quedan cortados, los paneles se pueden cerrar
y las funciones siguen accesibles. El scroll interno de una tabla no equivale
automáticamente a scroll horizontal general, pero debe permitir comprender y
operar la tabla. Registrar dimensiones, pantalla, captura y resultado. Probar
solo móvil no acredita este requisito de escritorio.

## 7. RNF-029: accesibilidad básica

**Criterio:** formularios principales etiquetados, foco visible, acciones
principales utilizables con teclado e imágenes relevantes con texto alternativo.

Por pantalla principal:

- Recorrer con Tab y Shift+Tab sin ratón: orden entendible y foco visible.
- Activar con Enter y, en controles que corresponda, Espacio. Comprobar selects,
  menús, formularios, apertura y cierre de paneles y retorno del foco.
- Comprobar que los campos tienen nombres accesibles; un placeholder solo no
  sustituye una etiqueta. Los botones de solo icono necesitan nombre comprensible.
- Revisar textos alternativos de imágenes relevantes. Las decorativas pueden
  tener alternativa vacía; no exigir descripciones innecesarias.
- Ejecutar una herramienta automática básica (por ejemplo axe o Lighthouse),
  registrar nombre, versión, pantalla, fecha y reporte. Resolver o explicar cada
  hallazgo; el reporte automático no sustituye la revisión con teclado.

Registrar defectos en [hallazgos-usabilidad.csv](hallazgos-usabilidad.csv).
No presentar esto como una certificación completa WCAG.

## 8. RNF-030: Chrome y Edge

**Criterio:** funciones principales operativas en las versiones estables actuales
de Chrome y Edge utilizadas durante la evaluación.

Instalar/actualizar ambos y registrar las versiones reales de la prueba. Ejecutar
la misma matriz en ambos navegadores: navegación, acceso, compra, publicación,
pedido, reclamación y acciones principales de los otros módulos por rol.
Añadir las tareas de direcciones, favoritos y reseñas ya disponibles.

Playwright puede aportar evidencia de los recorridos que efectivamente pruebe
en ambos proyectos (`chrome` y `edge`); una ejecución solo en Chrome no acredita
Edge, y las respuestas HTTP controladas no certifican la integración real.
Registrar pruebas manuales y automatizadas por separado, sin inventar comandos
o resultados para suites que no se hayan ejecutado.

## 9. Matriz de cierre y evidencias

| RNF | Resultado medido | Evidencias | Estado |
| --- | --- | --- | --- |
| 025 | Pendiente: personas que completan las cuatro tareas sin ayuda / 5 | Sesiones y resultados por tarea | Pendiente |
| 026 | Pendiente: promedio / 5, cinco respuestas | Encuestas anonimizadas | Pendiente |
| 027 | Pendiente: casos correctos / evaluados; pendientes del inventario | CSV de campos, capturas y pruebas | Pendiente |
| 028 | Pendiente: pantallas y resoluciones comprobadas | Matriz y capturas | Pendiente |
| 029 | Pendiente: revisión de teclado y hallazgos automáticos | Lista manual y reporte | Pendiente |
| 030 | Pendiente: tareas aprobadas en cada navegador | Matriz y versiones | Pendiente |

Por hallazgo registrar: RNF, rol/módulo, pasos, resultado esperado y observado,
severidad, evidencia, propuesta, responsable y estado. Después de corregir,
repetir la prueba y vincular las evidencias anterior y posterior. No eliminar
fallos del registro para mejorar el resultado.

Conservar capturas y reportes, por ejemplo, en `artifacts/usabilidad/<fecha>/`.
Esa carpeta se ignora en Git: exportar una copia depurada de secretos al medio de
entrega acordado con el equipo. En el informe final indicar enlaces y ubicación;
un archivo local no compartido no es evidencia accesible para el profesor.

## 10. Qué mostrar en la sustentación

Una diapositiva con objetivo, mapa de navegación resumido, decisiones de
arquitectura de información y una tabla de los seis RNF con resultados reales.
Respaldarla con el informe y demostrar en vivo un error de formulario con
recuperación, navegación por teclado y adaptación de una pantalla de escritorio.

Para RNF-025/026 presentar las sesiones y la encuesta; no reemplazarlas por una
demo automatizada. Mostrar un hallazgo real y su mejora, si existe. Explicar
pendientes y límites. Presentar «cumple» solo cuando los resultados y el alcance
lo respalden, no por haber preparado este documento.
