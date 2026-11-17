# ASR-004: evidencia de continuidad y recuperación

## Medición de recuperación ante caídas

`scripts/quality/asr004-recuperacion.sh` provoca dos fallas reales en el Swarm y mide cuánto tarda el sistema en
volver a funcionar. Mientras tanto consulta el catálogo cada segundo a través del frontend, como lo haría un usuario,
y cuenta las solicitudes que fallan.

```bash
bash scripts/quality/asr004-recuperacion.sh
```

Por cada falla:

1. Comprueba que el stack esté completo y sano: dos réplicas de backend, MySQL arriba y la API lista.
2. Mata un contenedor con `docker kill`, igual que si se cayera el proceso.
3. Cuenta el tiempo hasta que Swarm vuelve a tener todas las tareas corriendo, el readiness del backend (que incluye la
   conexión a MySQL) responde `UP` y el catálogo responde 200.
4. Compara el tiempo con el criterio del ASR: **5 minutos (300 s)**.

La evidencia queda en `artifacts/quality/asr004-<fecha>/`: `resultado.json` con los tiempos y `sondeo.csv` con cada
consulta al catálogo y su código HTTP.

### Resultado — 2026-11-17, Swarm en el PC de Alejandro

| Falla provocada | Recuperación | Criterio (≤ 300 s) | Solicitudes fallidas durante la falla |
|---|---|---|---|
| Se mata una de las dos réplicas del backend | **55 s** | Cumple | **0 de 49** |
| Se mata MySQL | **27 s** | Cumple | 8 de 12 |

Lectura de los resultados:

- **Backend:** mientras Swarm reemplazaba la réplica caída, la otra atendió todas las solicitudes. El usuario no vio
  ningún error. Es la táctica de *redundant spare* funcionando.
- **MySQL:** es una sola instancia, así que mientras se reinicia las solicitudes que necesitan la base fallan. Swarm la
  reinicia sola (`restart_policy: any`), el backend recupera sus conexiones sin reiniciarse y en 27 s todo vuelve a
  funcionar.

Al terminar, el stack quedó igual que al empezar: backend 2/2, frontend 2/2 y MySQL 1/1.

## Tácticas y dónde están en el código

| Táctica | Mecanismo | Evidencia |
|---|---|---|
| Monitor / heartbeat | `HEALTHCHECK` en los Dockerfile del backend y del frontend, cada 10 s. Spring Actuator con `liveness` y `readiness`; la de readiness incluye la base de datos | `backend/demo/Dockerfile`, `frontend/Dockerfile`, `application.properties` (`management.endpoint.health.group.readiness.include=readinessState,db`) |
| Redundant spare | Dos réplicas de backend y dos de frontend, como máximo una por nodo en el clúster real | `stack.yml` (`replicas: 2`, `max_replicas_per_node`) y la medición de arriba (0 errores al caer una réplica) |
| Reconfiguración / recuperación | Swarm reinicia toda tarea que muere (`restart_policy: condition: any`). Las actualizaciones van de a una y, si fallan, se revierten solas (`failure_action: rollback`) | `stack.yml`, `deploy.sh` y la medición de arriba |
| Manejo de excepciones | Resilience4j con circuit breaker hacia logística, y timeouts de máximo 10 s en todo cliente externo | `application.properties` (`resilience4j.circuitbreaker.instances.logistics.*`), `ExternalRestClients` |
| Rollback de datos | Transacciones de Spring sobre MySQL: un fallo a mitad de una operación deshace todos sus cambios | `SellerCancelFaultInjectionTests` y `SellerOrdersFaultInjectionTests` (inyectan fallos en cada punto de la transacción y comprueban que no cambia nada: estado, stock, reembolso, historial, auditoría y notificaciones). `CheckoutOrderCreationTests` (un carrito inválido no deja nada a medias) |
| Recuperación de pagos | El pago con Mercado Pago se puede reconsultar y completar después de una interrupción | `MercadoPagoH05RecoveryTests`, `MercadoPagoCheckoutCancellationTests` |
| Graceful degradation | Si Gemini no responde, las recomendaciones pasan a un ranking general en vez de fallar | `RecommendationServiceTests`, `RecommendationFlowIntegrationTests` |
