# Pruebas en vivo de atributos de calidad

Cambios locales posteriores a la auditoría del SHA `770f3c6da98a1885b69bf43c606a60986b2468e6`. No están publicados ni desplegados. **No se modificaron CUs, frontend funcional, backend, seeds, tests de CUs ni gates de cobertura.** Esta receta sustituye las instrucciones anteriores de medición manual cuando se usa la versión local modificada.

## Alcance respecto al enunciado

| Exigencia | Soporte disponible | Qué falta comprobar en el entorno real |
|---|---|---|
| Pruebas en vivo y resultados cuantitativos | Ejecutor de performance 50/100 VUs y observador de disponibilidad; logs, JSON, SHA/árbol de trabajo y versión k6 | Ejecutarlos contra el sistema real, guardar resultados sin escoger solo corridas verdes |
| Dos o más computadores | Stack limita a una réplica frontend/backend por nodo; deploy real exige ≥2 nodos Linux Ready/Active y comprueba placement al converger | Asociar cada NodeID a un computador físico diferente; resolver red de Docker Desktop |
| Inicio por único script | `bash deploy.sh`, cluster y credenciales previamente configurados | Ejecutarlo en el manager; no confundir preparación del cluster con arranque de servicios |
| Pipeline CI/CD | Publicación espera backend, E2E y verificación de herramientas; CD existente depende de publicación | Runner `self-hosted, swarm-manager` operativo, acceso GHCR y variables production; nueva ejecución real |
| Todos los atributos vistos en clase | Se corrigió lo identificable en repo: desempeño, disponibilidad y desplegabilidad | El enunciado no enumera otros atributos ni componentes de clase; SAD/SRS original no está disponible aquí |
| 100 % integración backend | Sin cambios: requisito asociado a CUs, fuera del alcance solicitado | No acreditado por esta tarea; no se rebajó ni maquilló coverage |

No prometer cumplimiento total solo porque estos archivos existen. MySQL sigue sin HA. Dos motores en un solo computador no cumplen distribución física. La prueba de disponibilidad interrumpe UNA réplica de backend, no el nodo ni la DB.

## Qué cambió, lo mínimo

- `catalog-browse.js` exige cuenta de prueba verificada y valida login, listado no vacío y detalle antes de cargar. Ya no registra cuentas durante setup. Mantiene umbrales P95 ≤3 s y error <2 %, añade exigencia de al menos un recorrido completo exitoso y contadores exactos de requests de catálogo, respuestas 200 y fallidas.
- `scripts/quality/run.py` guarda corridas separadas sin sobrescribir evidencia. `performance` ejecuta 50 y 100 VUs durante 1 min cada una, secuencialmente. `availability` ejecuta 50 VUs durante 3 min y observa tasks/readiness desde manager cada ~2 s más latencia de comandos.
- `deploy.sh` rechaza modo real con menos de dos nodos Linux Ready/Active **antes de pulls/secrets/deploy**. Al converger, frontend y backend deben estar en nodos distintos. `--local` permite dos réplicas por nodo y sigue siendo solo ensayo local.
- `stack.yml` limita cada servicio de aplicación a una réplica por nodo en modo real. Actualiza una a la vez con `stop-first`: evita esperar una tercera task sin slot libre en un cluster de dos nodos. Durante reemplazo puede quedar una réplica; no se promete cero interrupción. No se cambia placement/volumen MySQL.
- CI espera `validate`, `frontend-e2e` y `quality-checks` antes de construir/publicar. No se altera ninguna prueba funcional. Los checks de herramientas **no son una certificación de los atributos reales**.

## Antes del reloj de 20 minutos

1. Confirmar IP manager, red entre motores, dos PCs, NodeIDs y puertos; usar §3 y §14 de la [guía larga](GUIA-SUSTENTACION-EN-VIVO.md).
2. Preparar entorno separado, imágenes publicadas del commit que se vaya a demostrar y cuenta verificada/datos no vacíos. El stack no activa perfil demo por sí solo. No cargar seeds en datos importantes.
3. En manager instalar previamente Python 3 y k6; los comandos siguientes usan k6 nativo. En Windows usar Git Bash con `python3` accesible, o PowerShell con `py` como se indica.
4. Tener dos terminales en manager (medición y control) y una en worker. No correr builds/suites ni relanzar launcher durante carga.
5. No usar el SHA base para identificar modificaciones locales como si ya estuvieran publicadas: el executor guarda SHA y `git status`. Publicar nuevas imágenes requerirá el flujo de entrega autorizado por el equipo; esta tarea no hizo commit/push.

## Performance — cliente de carga

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER/cliente de carga, Bash de macOS/Linux, raíz del repositorio. La IP y puerto son el ejemplo de la guía: confirmar que es EL frontend Swarm medido.**
```bash
cd /Users/sofiamantilla/Documents/GitHub/Transformers-AS
read -r -p 'Correo de cuenta de prueba verificada: ' CATALOG_EMAIL
read -r -s -p 'Contraseña de prueba: ' CATALOG_PASSWORD
printf '\n'
export CATALOG_EMAIL CATALOG_PASSWORD
python3 scripts/quality/run.py performance --base-url http://192.168.40.13:18000
```

La ruta cd anterior es solo el Mac de Sofía. En otro PC entrar a su raíz real. Las credenciales se heredan por entorno; no se imprimen ni se guardan en los JSON propios del executor. No mostrar inspect de procesos/entornos en la proyección.

**DÓNDE EJECUTAR ESTO — 💻 PC Windows cliente, PowerShell, YA situado en la raíz real del repo; alternativa al bloque Bash.**
```powershell
$env:CATALOG_EMAIL = Read-Host 'Correo de cuenta de prueba verificada'
$claveCarga = Read-Host 'Contraseña de prueba' -AsSecureString
$env:CATALOG_PASSWORD = [System.Net.NetworkCredential]::new('', $claveCarga).Password
py scripts/quality/run.py performance --base-url http://192.168.40.13:18000
```

🟢 Esperado: ambas corridas completas, exit 0, métricas y thresholds satisfechos. 🔴 Cuenta inválida/catálogo vacío: aborta setup, no acredita atributo. El executor conserva el FAIL y aun así permite obtener la segunda corrida si la primera solo falló por thresholds. Elimina overrides heredados `P95_LIMIT_MS` y `ERROR_RATE_LIMIT`; no rebaja límites.

Salida en `artifacts/quality/<fechaUTC>-<PID>/`: `context.json`, `catalogo-50.log`, `catalogo-50.json`, `catalogo-50-result.json` y equivalentes 100. `artifacts/` ya está ignorado por Git. El log se escribe mientras corre; el executor lo imprime al finalizar cada corrida. Para ver avance, abrir el archivo `.log` de la carpeta anunciada en el editor, en el mismo PC.

Anotar p95 listado, p95 detalle, `catalog_error_rate`, `catalog_throughput` count/rate y `http_reqs`. Nuevos `catalog_requests = catalog_successes + catalog_failures` cuentan HTTP de listado/detalle durante carga, **no** setup/login; successes significa HTTP 200, no certifica semántica completa de un cuerpo inválido. El rate de negocio sigue detectando catálogo vacío. No convertir las Rates HTTP en contadores de éxito con semántica invertida.

## Availability — observar sin matar automáticamente

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, terminal Bash de medición en raíz, MISMAS variables de cuenta ya exportadas.**
```bash
python3 scripts/quality/run.py availability --base-url http://192.168.40.13:18000 --stack transformers
```

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER Windows, PowerShell en raíz, variables de cuenta de su bloque previo; alternativa.**
```powershell
py scripts/quality/run.py availability --base-url http://192.168.40.13:18000 --stack transformers
```

Debe iniciar con dos backends Running en nodos distintos y readiness proxy UP. No basta usar el stack antiguo marketplace del Mac para afirmar despliegue de esta versión.

1. Esperar ~30 s de tráfico. Si k6 aborta por setup, **no matar nada**: leer `.log`.
2. En terminal de control del manager, identificar task y NodeID del worker mediante §3.4 de la guía larga.
3. En worker, comprobar Service y TaskID del contenedor local; ejecutar el kill de §8.3 **solo de esa réplica**. Anotar hora exacta y cronómetro manual; el executor no ejecuta kill.
4. Dejar terminar los 3 min. El observador guarda `availability-samples.jsonl` y `availability-result.json`, junto al resumen/log k6.
5. Abrir resultado y capturar tasks antiguas/nuevas, métricas y tiempos. No marcar prueba física como verificada hasta relacionar NodeIDs con ambos PCs.

**Definición precisa del recovery automático:** desde primera muestra donde falta una task original Running hasta la tercera muestra consecutiva con dos tasks Running en nodos distintos, una nueva task y readiness proxy UP. Es **recuperación observada por sondeo**, no instante exacto del kill ni health individual de cada contenedor. Conserva muestras y valida salud individual manualmente en cada nodo, como §8.4. Si se necesita tiempo desde kill, usar además cronómetro/timestamps manuales.

FAIL si no se observa pérdida/reemplazo, si también desaparece la segunda task original o si falla k6. El JSON no convierte dos nodos en dos computadores: mantiene `physical_computers_verified: false`; acompañarlo con evidencia física. Un redeploy o reinicio ajeno también puede reemplazar task: registrar manualmente la causa de la caída.

## Desplegabilidad y CI/CD

Preparar el cluster con §14 y seguir §9 de la guía en vivo: existe constraint de máximo una réplica por nodo, las actualizaciones usan stop-first y deploy real exige al menos dos nodos Linux. La configuración previa no cambia: GHCR/tag, puertos, secrets y red listos; script único en manager. Esto no crea el segundo computador ni corrige NAT de Desktop.

**DÓNDE EJECUTAR ESTO — 💻 PC MANAGER, Bash, raíz, SOLO tras configurar tags publicados/puertos/secrets según §3.1 y §14.3 de la guía en vivo.**
```bash
bash deploy.sh
```

No ejecutado por esta tarea. En un solo nodo real ahora falla explícitamente; `--local` se reserva a ensayo declarado, no a certificar dos PCs.

En GitHub mostrar nueva ejecución de `Backend CI and GHCR`, dependencias `quality-checks` + Maven + E2E → publish → deploy. Si falta runner self-hosted, reconocer CD pendiente: un cambio YAML no crea ese runner. No se han inventado componentes de clase que el enunciado no enumera.

## Validación de las herramientas, no del ASR

**DÓNDE EJECUTAR ESTO — 💻 PC de desarrollo, Bash, raíz; solo tests de herramientas y sintaxis, sin levantar/matar servicios reales.**
```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/quality/tests -v
bash -n deploy.sh
```

Los tests HTTP usan un servidor simulado local con k6, no el Marketplace. Si k6 falta, esos tests se marcan skipped; no son una corrida de carga válida para presentar al profesor. Las pruebas del observador cubren ausencia de caída, doble caída, falta de distribución y recuperación estable. No afirmar atributos implementados correctamente hasta completar las mediciones reales con evidencia.

**Resultado observado de validación local (Mac de Sofía):** 11 tests de herramientas aprobados, incluidos cuatro contratos HTTP con k6 contra servidor simulado y el rechazo de despliegue real de un solo nodo antes de mutaciones. También pasaron sintaxis Bash/JavaScript, parseo del workflow y referencias de sus jobs, render de stack para límites 1 y 2 por nodo y revisión de whitespace. No se ejecutaron carga, caída de réplica ni deploy contra el Marketplace activo; no hay resultado ASR real nuevo que atribuir a estas validaciones.
