# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Monorepo for a university marketplace project (team "Transformers", Javeriana). Docs and code comments are mostly in Spanish.

- `backend/demo/` — Spring Boot 4.1.1 / Java 21 modular monolith (Maven, MySQL 8.4.11, Flyway). The folder name `demo` is kept for Docker/CI paths; the Maven artifact is `marketplace-backend` and the base package is `com.transformersas.marketplace`.
- `frontend/` — Ionic 8 + Angular 18 standalone app (PWA, Capacitor-ready). Web is the only official target.
- `compose.yaml` — local dev (MySQL + backend + nginx frontend on :4300). `stack.yml` + `deploy.sh` — Docker Swarm demo deployment (see `docs/swarm.md`).
- `scripts/` — dev-only demo data and tools: `cu23-demo-seed.sh` (seller/buyer accounts, orders in several states), `cu24-25-demo-seed.sh` (orders ready for dispatch with a shipment, and returns awaiting pickup) and `cu24-25-demo-events.sh` (plays the logistics provider: sends HMAC-signed events to the webhook); see "Trying CU-23 from the UI" and "Trying CU-24 / CU-25 from the UI" below.
- `docs/` — `Decisiones_Arquitectonicas_Marketplace.md` (ADR for the course) and `swarm.md`. The provider contracts (`docs/contracts/`), `docs/testing/coverage.md` and the CU-23/24/25 guides were deliberately removed from the repo on 2026-09-20 (still in git history: `git show 82a41509:docs/contracts/logistics-api.md`, `git log --diff-filter=D -- docs`), so the essentials live in this file; code comments and `application.properties` that mention `docs/contracts/...` point to those removed files. Do not recreate docs in the repo unless asked.

## Commands

### Backend (run from `backend/demo`; use the wrapper, JDK 21 required)

```bash
./mvnw clean verify                 # full build + tests + JaCoCo gate (Windows: mvnw.cmd)
./mvnw test -Dtest=BusinessApiIntegrationTests                                  # one test class
./mvnw test -Dtest=BusinessApiIntegrationTests#cartLifecyclePersistsAndCalculatesTotals   # one test method
```

Tests are `@SpringBootTest` + Testcontainers (`mysql:8.4.11`), so **Docker must be running**; they need no `.env` or Compose. Reports: `target/site/jacoco/index.html`, `target/surefire-reports/`.

- **Coverage gate**: `verify` runs `jacoco:check` and fails below **95 % global line coverage** (CI is `.github/workflows/backend-ci.yml`). Use `mvnw test -Dtest=...` for subsets: a partial run through `verify` passes its tests but ends in BUILD FAILURE on the gate.
- The suite has ~800 tests and several Spring contexts/containers, so a full `verify` takes tens of minutes. CU-23 tests extend `support/AbstractIntegrationTest` (one shared MySQL container, real session login, `performAsSeller`, seeding helpers, per-test cleanup: add new tables to its `TABLES_TO_CLEAR` in FK order); CU-24/25 tests extend `support/AbstractTrackingTest` (adds signed-webhook helpers and seeding of shipped orders and return shipments; `src/test/resources/config/application.properties` sets the test webhook secret and turns the polling sweep off; dates relative to "now" must come from the JVM, not MySQL's `NOW()`, which runs in another time zone); older suites (`OrderOwnershipIntegrationTests`, `SupportApiIntegrationTests`, …) bring their own `@Container` and their own cleanup lists.
- Async behavior is asserted with Awaitility (never `sleep`), provider behavior with WireMock.
- On Windows without Docker Desktop, run Maven from WSL (Docker Engine + JDK 21), preferably from a copy on the WSL filesystem: `/mnt/c` is very slow. From PowerShell, quote `-D` arguments (`"-Dtest=A,B"`) and put multi-command logic in a script file.

### Running the backend

```bash
cp .env.example .env                # first time only; set DB_PASSWORD and MYSQL_ROOT_PASSWORD (never commit .env)
docker compose up -d --build --wait # MySQL on host :3307, backend on 127.0.0.1:8080, frontend (nginx, proxies /api) on 127.0.0.1:4300
curl --fail http://localhost:8080/actuator/health/readiness
```

Add `SPRING_PROFILES_ACTIVE=local` to `.env` (dev only) to make the backend provision `demo@marketplace.local` (roles `COMPRADOR`+`VENDEDOR`, password in `LocalDemoAccountConfiguration`) and the product "Camiseta demo local" (store 1); with it you can buy as COMPRADOR, switch the active role to VENDEDOR and handle the order in the UI. For a second, isolated stack use `docker compose -p <name> --env-file <file>` with different `FRONTEND_PORT`/`BACKEND_HOST_PORT`/`MYSQL_HOST_PORT`.

To run Spring Boot from the host instead: `docker compose stop backend`, `docker compose up -d --wait mysql`, export `.env` into the shell (`set -a; source .env; set +a`), then `./mvnw spring-boot:run` in `backend/demo`. `DB_PASSWORD` has no default in `application.properties`, so it must be set. With plain `docker run` for MySQL, use `DB_PORT=3307`.

### Frontend (run from `frontend`)

```bash
npm install
npm start          # ng serve on http://localhost:4300, /api proxied to http://localhost:8080
npm run build      # production build incl. service worker -> dist/browser
```

`npm test` is declared but no Karma/Jasmine tooling or spec files exist. There is no lint setup. To try CU-23 end to end from the UI see "Trying CU-23 from the UI" below.

## Backend architecture

### Two coexisting package styles

`backend/demo/ARCHITECTURE.md` prescribes a per-module hexagonal layout (`domain/{model,repository}`, `application/{dto,usecase}`, `infrastructure/{persistence,web}`) with rules such as "controllers call one use case", "domain models are not JPA entities", and "a module never touches another module's entities or repositories". `orders`, `payments`, `logistics`, `notifications`, `inventory`, `users`, `auth` and `reports` (CU-21 moderation) follow it; newer modules use `JdbcClient` adapters for append-only tables instead of JPA entities. `returns` (CU-19) now follows it too (its `LogisticsShipmentRegistrar` and `ReturnEventListeners` are the bridge to the logistics tracking below); `catalog` and `reviews` are still empty skeletons containing only `package-info.java`.

The working code for the product/cart/checkout use cases (`product`, `cart`, `address`, `checkout`, `reservation`) is **flat and layered** (`XController`, `XService`, `XRepository`, JPA entity, `dto/`), and it violates the module-isolation rule: `checkout`, `reservation` and `orders` use `cart`'s JPA repositories and `Product` directly. Match the style of the module you are editing; when adding a new business module, follow `ARCHITECTURE.md`.

Beware of look-alikes: there are **two audit mechanisms** (`audit` module → `AuditService`/`audit_logs` for moderation, and `shared/audit/AuditRecorder` → `audit_events` for orders/logistics/refunds) and **two notification mechanisms** (`notifications` module → `PublishNotificationUseCase`/`notifications` table, and `reports.application.NotificationDispatcher` → `moderation_notifications`). Use the one that belongs to the use case you are working on.

### Checkout / payment flow (spans several modules)

The purchase flow is orchestrated by `payments.application.usecase.ProcessPaymentUseCase` (single `@Transactional`), for the authenticated buyer (`PaymentController` returns 401 without an `AccountPrincipal`):

1. `POST /api/reservations/cart` → `InventoryReservationService.reserveCart()` creates `ACTIVE` reservations (10-minute TTL) for every cart item. Availability = `product.stock − sum(ACTIVE, unexpired reservations)`. Physical `stock` is **not** decremented at this point.
2. `POST /api/checkout/preview` → `CheckoutService.preview()` re-validates address, cart and products and computes subtotal, coupon and shipping. Shipping: `STANDARD` 10000 / `EXPRESS` 20000. The only coupon is `DESC10` (10%); an invalid coupon does **not** fail the request (CU-03 alternate flow A3), it just returns `couponValid=false`.
3. `POST /api/payments/process` → `ProcessPaymentUseCase` **recomputes the total via `CheckoutService`** (never trusts a client total, nor a client-supplied owner), then calls the `PaymentGateway` port:
   - `APPROVED` → `confirmReservations` (decrements stock, marks `CONFIRMED`) then `CreateOrderUseCase` (validates the account, creates a `CONFIRMED` order owned by it with its `store_id` and delivery snapshot, writes the initial history row, deletes the cart items). A cart with products from several stores fails with 409 `MULTI_STORE_CART` **after** payment approval (CU-03 code is untouched).
   - `REJECTED` → `releaseReservations`.
   - `PENDING` → nothing; reservations stay `ACTIVE` until they expire.
4. Expiry is lazy: `expireOldReservations()` runs at the start of reserve/confirm; there is no scheduler.

The only `PaymentGateway` implementation is `MockPaymentGateway`, which keys off the `paymentMethod` string: `CARD` → approved, `TEST_REJECT`, `TEST_PENDING`, `TEST_UNAVAILABLE` (503); anything else → 400.

### Orders: buyer side and seller fulfilment (CU-11 / CU-23)

- **Order states** (`OrderStatus`, 11 values): `CONFIRMED`, `IN_PREPARATION`, `READY_FOR_DISPATCH`, the transport states (`PICKED_UP`, `IN_TRANSIT`, `DELIVERY_EXCEPTION`, `DELIVERY_ATTEMPT_FAILED`, `DELIVERED`, `RETURNED_TO_SELLER`), `CANCELLED`, and `CANCELLATION_REQUESTED`. Buyer/seller have only four transitions (`CONFIRMED → IN_PREPARATION | CANCELLED`, `IN_PREPARATION → READY_FOR_DISPATCH | CANCELLED`), defined in `OrderStatus.allowedTargets()`; the transport states are reached only through `allowedLogisticsTargets()` when the logistics provider reports them (see the next section).
- **Buyer** (`/api/orders`, role `COMPRADOR`): list/detail of own orders (`FindOwnOrders`) and `POST /{id}/cancellation` (`RequestOrderCancellation`), which only moves `CONFIRMED → CANCELLATION_REQUESTED`. Nothing resolves that state yet (CU-11 pending), and that request writes no history or audit.
- **Seller** (`/api/seller/orders`, needs the `VENDEDOR` active role + `X-Store-Id`): list (filters `status`, `from`, `to`, `orderId`, `page`, `size`; default `CONFIRMED`+`IN_PREPARATION`), detail, `POST /{id}/start-preparation`, `/issues`, `/issues/{issueId}/resolve`, `/ready-for-dispatch`, `/shipment` (manual retry: 201 created now, 200 already existed) and `/cancel` (`reasonCode` `OUT_OF_STOCK|PRODUCT_DAMAGED|OTHER`, `OTHER` needs details). Stable error codes: 400 `INVALID_PAGINATION`, `INVALID_DATE_RANGE`, `CANCELLATION_DETAILS_REQUIRED`; 401 `UNAUTHENTICATED`, `STORE_IDENTITY_MISSING`; 403 `SELLER_ROLE_REQUIRED`; 404 `ORDER_NOT_FOUND`, `ISSUE_NOT_FOUND` (another store's order is a 404, never a 403); 409 `ORDER_INVALID_TRANSITION`, `PAYMENT_NOT_APPROVED`, `INVENTORY_INCONSISTENT` (also opens an issue; inconsistent = product missing or negative stock, since stock is already decremented at payment), `ORDER_STATE_CONFLICT`, `OPEN_ISSUES`, `ISSUE_ALREADY_OPEN`, `ISSUE_ALREADY_RESOLVED`, `ISSUE_NOT_ALLOWED_IN_STATUS`, `ORDER_NOT_READY_FOR_DISPATCH`, `ORDER_HAS_SHIPMENT`, `ORDER_ALREADY_CANCELLED`, `MULTI_STORE_CART`; 502 `SHIPMENT_PROVIDER_FAILED`. `ready-for-dispatch` returns 200 even if the provider fails (`shipment.status` `FAILED`; the order stays ready and the shipment is retried manually). CU-11 (buyer) should reuse `orders.CancelOrderUseCase` with initiator `BUYER` after checking ownership.

Rules that apply to anything touching orders:

- **State changes are compare-and-set** (`OrderRepository.transitionStatus(id, from, to)` = `UPDATE ... WHERE status = :from`); history, audit and notifications are written only if it affected a row, all in the same transaction.
- **External calls never run inside a DB transaction**: provider calls (logistics, external notices, refunds) happen after the commit, with a ≤10 s timeout (`shared/http/ExternalRestClients`), a Resilience4j breaker per integration, and no automatic retries. Providers have `simulated` (default) and `http` adapters (`logistics.provider`, `notifications.provider`); simulated failure modes come from `logistics.simulated.mode`, `notifications.simulated.mode` and `payments.refund.simulated.mode` (also settable as env vars).
- `OrderMapper.newEntity` only inserts; never use it to update an order. Orders carry `store_id` and an immutable `delivery_*` snapshot copied at purchase — read that, never `addresses`. The buyer is `orders.account_id` (`NULL` only for historical orders).
- Cross-module calls go through the other module's use case (`GetStockLevelsUseCase`, `RestoreStockUseCase`, `RequestRefundUseCase`, `PublishNotificationUseCase` (`MANDATORY` tx), `CreateShipmentUseCase`).
- Errors with a stable `code` use `shared/error/BusinessException`; request DTOs for actions implement `StrictJsonRequest` so unknown properties return 400.
- Every request gets an `X-Correlation-Id` (`CorrelationIdFilter`) that is propagated to history, audit, notifications, refunds and provider calls.

### Logistics tracking: orders (CU-24) and returns (CU-25)

The `logistics` module owns the shipment/return timelines, the provider gateway and the webhook; the order state itself is still `orders.status`.

- **Two entry points, one use case.** The provider pushes to `POST /api/logistics/webhooks/{shipments|returns}` (no session, no CSRF; authenticated by the HMAC-SHA256 of the raw body in `X-Logistics-Signature` with `logistics.webhook.secret`; an empty secret keeps the webhook closed, 401 `WEBHOOK_NOT_CONFIGURED`), or the marketplace polls: `TrackingPoller` every `logistics.tracking.polling-interval` (30 s; each sweep polls what has gone more than *half* the interval unpolled, otherwise the fixed delay would skip every other sweep) and the «Actualizar seguimiento» endpoints, throttled by `refresh-min-interval`. Both end in `ProcessShipmentUpdateUseCase` / `ProcessReturnUpdateUseCase`, the only code that changes transport state; buyer and seller can only look and ask for a re-poll (A7/A8).
- **Idempotency and ordering.** Each update is a row in `shipment_tracking_events` / `return_tracking_events` with `UNIQUE (shipment|return, provider_event_id)`; a repeated id answers `DUPLICATE` and has no effect. The transaction starts with `SELECT ... FOR UPDATE` on the shipment/return row (it serializes concurrent updates; without it the FK shared lock followed by the CAS exclusive lock deadlocks), inserts the event as `RECORDED`, then applies it: `APPLIED` (state moved), `RECORDED` (extra information, e.g. a second `IN_TRANSIT`) or `OUT_OF_ORDER` (would move backwards; kept only for traceability). A lost CAS or a deadlock rolls everything back and retries up to 3 times, then 409 `ORDER_STATE_CONFLICT` / `RETURN_STATE_CONFLICT`.
- **Module boundary.** Logistics never touches `orders`: it calls `OrderTrackingPort` (in `logistics.domain.repository`), implemented by `orders.application.usecase.ApplyLogisticsUpdateUseCase` (`Propagation.MANDATORY`): CAS with `OrderStatus.allowedLogisticsTargets()`, history (actor `LOGISTICS`), audit `ORDER_STATUS_CHANGED`, notifications keyed `order-{id}-{STATE}-{providerEventId}` (`OrderChangeRecorder` overloads that take an event id). `DELIVERED` and `RETURNED_TO_SELLER` close the polling (`shipments.tracking_active = FALSE`).
- **Views.** `GET /api/orders/{id}/tracking` (COMPRADOR, own orders) and `GET /api/seller/orders/{id}/tracking` (the store), each with `POST .../tracking/refresh` (empty or `{}` body only: a state in the body is 400); other people's orders are 404 `ORDER_NOT_FOUND`. Returns: `/api/returns/{id}/tracking` and `/api/seller/returns/{id}/tracking` (+ `/refresh`), 404 `RETURN_NOT_FOUND`. The response has the timeline in chronological order (each event with its `outcome`), `tracking` (still polled), `lastPollFailed` (the last known state is shown, A7), `refresh` (`UPDATED|NO_CHANGES|UNAVAILABLE|THROTTLED|NOT_TRACKED`) and the delivery date/evidence. `SecurityConfiguration` lists these buyer paths explicitly (`hasRole("COMPRADOR")`).
- **Returns (CU-19 asks, CU-25 tracks).** There is no `return_id` FK: `return_shipments.return_id` is the id of a CU-19 return request (`return_requests`, V30), kept opaque so `logistics` never touches `returns`. CU-19 creates the logistics reference and calls `RegisterReturnShipmentUseCase` through `returns...LogisticsShipmentRegistrar` (idempotent; leaves the return `PICKUP_PENDING`, joins the caller's transaction); when the provider reports delivery, `logistics` publishes `ReturnDeliveredToSeller` in the same transaction and `returns...ReturnEventListeners` starts the inspection. States are `ReturnStatus` (`PICKUP_PENDING, PICKED_UP, IN_RETURN, LOGISTICS_ISSUE, PICKUP_FAILED, DELIVERED_TO_SELLER`) with the rules in `ReturnTransitions`; the third failed pickup sets `pickup_stopped`, closes the polling, notifies both parties, audits `RETURN_PICKUP_STOPPED` and rejects every later update. The buyer's «new pickup option» (A1) is not implemented in `logistics`: it belongs to CU-19's return-method selection.
- **Provider.** `LogisticsGateway` also has `fetchShipmentUpdates` / `fetchReturnUpdates` (GET `/shipments/{id}/events`, `/returns/{id}/events`; same timeout, breaker and no-retry policy). The simulated gateway returns whatever the tests queue (`publish` / `publishReturn`) or, with `logistics.simulated.script=HAPPY_PATH`, advances by itself every `logistics.simulated.step`. Shipment `SIM-order-N` has tracking code `TRK-N`; return `SIM-return-N` has `TRK-RN`.

### Known simplifications to keep in mind

- **Auth is session-based (CU-08)**: `/api/**` needs a session (cookie `SESSION`), CSRF is enabled (`/api/auth/csrf`), roles are `COMPRADOR|VENDEDOR|ADMIN|SOPORTE` with one active role. Password change and recovery live under `/api/auth`. `SecurityConfiguration` gates `/api/support/**` to `SOPORTE` and `/api/orders/**` to `COMPRADOR`; seller endpoints only require a session and check the `VENDEDOR` active role in `SessionSellerActorProvider`. The seller's **store is provisional** (`X-Store-Id` header, not checked against the account) until CU-18 defines account-store ownership.
- There is still a single global cart (`cartRepository.findAll().stream().findFirst()`), shared by all buyers.
- CORS is configured globally in `SecurityConfiguration` (`app.cors.allowed-origins`, credentials allowed); six legacy controllers still carry `@CrossOrigin(origins = "*")`.
- Legacy errors are `ResponseStatusException` rendered by `shared/ApiExceptionHandler` as `{status, error, message, path}`; `BusinessException` adds `code` and optional `details`.
- `payments` has two `PaymentRequest`/`PaymentResponse` pairs (application DTO vs. web request/response); the web layer maps between them. `PaymentGateway` and `RefundGateway` only have simulated adapters.
- Spring Cache/Caffeine (`@EnableCaching`) is on the classpath but unused — no `@Cacheable`. Resilience4j breakers exist only for `logistics` and `notifications`.
- No read endpoints exist for notifications or refunds; `payment_status` is not reconciled if a pending refund completes later.
- Return tracking is reachable only for ids registered in `return_shipments` (CU-19 does it when the return method is chosen; `scripts/cu24-25-demo-seed.sh` does it by SQL with ids 9001–9003, which are not real `return_requests`). Every backend replica runs the polling sweep (safe: deduplication lives in the DB); `stack.yml` does not yet provide the `logistics.webhook.secret` secret, so under Swarm the webhook stays closed until it is created.

### Database

- Flyway owns the schema (`src/main/resources/db/migration/`, currently V1–V30): V1–V5 core (products, carts, addresses, reservations, orders), V6–V7 accounts and JDBC sessions, V8–V9 moderation (reports, cases), V10 password-recovery tokens, V11–V12 `orders.account_id` and wider `status`, V13–V19 CU-23 (stores/snapshot, status history, `audit_events`, shipments, notifications, order issues, cancellations/refunds), V20–V21 CU-24/CU-25 (`shipment_tracking_events` + polling columns on `shipments`; `return_shipments` + `return_tracking_events`), V22–V30 from other teams (store configuration, catalog, seller products, claims, report evidence, interactions, seller registration, stock control, `return_requests`). Hibernate runs with `ddl-auto=validate`, so every entity change needs a **new** migration numbered after the highest one on `origin/main`; never edit an applied one and never use `update`/`create`. Another branch may have taken a number: renumber yours when merging (CU-19 already moved its migration to V30 for that reason).
- Tests build the schema through the same Flyway migrations against a fresh Testcontainers MySQL. Suites that wipe business tables must include the child tables of `orders` (`order_status_history`, `shipments`, `shipment_tracking_events`, `order_issues`, `order_cancellations`, `refunds`, `notifications`, `audit_events`) and the return tables (`return_shipments`, `return_tracking_events`) in FK order, and raw `INSERT INTO orders` needs `store_id` and the `delivery_*` columns (`NOT NULL`, no default).
- Actuator exposes only `health`; the readiness group includes `db`, the Docker healthcheck uses liveness.

### Stale docs

`README.md` (root) and `db/migration/README.md` still say there are no use cases and no V1 migration; that predates the cart/checkout/payment work (schema is at V30). Trust the code and migrations over any older statement. The rest of the README (setup, team rules) is still valid: branch per change (never commit to `main`), merge `origin/main` and run `./mvnw clean verify` before integrating.

### Trying CU-23 from the UI

1. `.env` with `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD` and `SPRING_PROFILES_ACTIVE=local`; then `docker compose up -d --build --wait` and open `http://localhost:4300`.
2. Log in with `demo@marketplace.local` / `MarketplaceDemo123!` (created only by the `local` profile), pick the active role **COMPRADOR** in the account panel, and **reload the page** (products are fetched before login, so they only appear after a reload).
3. Add "Camiseta demo local" → cart → *Continuar compra* → address → *Calcular total* → *Confirmar compra* (`CARD` is approved).
4. Switch the active role to **VENDEDOR**: the *Pedidos recibidos* button appears. Prepare, register/resolve issues, mark ready for dispatch or cancel. Back as COMPRADOR, *Mis pedidos* shows the resulting state.
5. Extra cases: `scripts/cu23-demo-seed.sh` (env `MYSQL_CONTAINER`, `DB_PASSWORD`, `DEMO_PASSWORD`; repeatable) creates `vendedor.demo@example.com` / `comprador.demo@example.com` and six orders, including one whose product no longer exists (inventory inconsistency) and one ready for dispatch without a shipment (retry). Provider failures: a local, uncommitted `compose.override.yaml` setting `LOGISTICS_SIMULATED_MODE`, `PAYMENTS_REFUND_SIMULATED_MODE` or `NOTIFICATIONS_SIMULATED_MODE`. Notifications, refunds and audit are only visible in the DB (`docker compose exec -T mysql sh -c 'mysql -umarketplace_app -p$MYSQL_PASSWORD marketplace'`, SQL on stdin).

### Trying CU-24 / CU-25 from the UI

1. `.env` as for CU-23 plus `LOGISTICS_WEBHOOK_SECRET`, and either `LOGISTICS_SIMULATED_SCRIPT=HAPPY_PATH` (with `LOGISTICS_SIMULATED_STEP=20s`: every shipment and return advances by itself; the backend polls every 30 s and the UI re-reads every 10 s) or nothing (you send the events by hand). Then `docker compose up -d --build --wait`.
2. Buy and dispatch as in CU-23, or load the seeds from Git Bash at the repo root: put Docker on its `PATH` (`export PATH="$PATH:/c/Users/<user>/AppData/Local/Programs/DockerDesktop/resources/bin"`), `set -a; source <(sed 's/\r$//' .env); set +a`, `export DEMO_PASSWORD=... MYSQL_CONTAINER=transformers-as-mysql-1`, then `bash <(sed 's/\r$//' scripts/cu23-demo-seed.sh)` and `bash <(sed 's/\r$//' scripts/cu24-25-demo-seed.sh)` (the `sed` strips the CRLF that autocrlf leaves in the scripts; no `.gitattributes` exists). The second seed makes 3 orders ready for dispatch **with** their shipment and returns 9001 to 9003 awaiting pickup, owned by `comprador.demo@example.com` in store 1; it prints their ids (on an empty DB #7 to #9). Do not mistake `demo-cu23-04` (ready for dispatch *without* shipment, to try the retry) or `demo-cu23-06` (*Cancelación solicitada*) for tracking orders.
3. As COMPRADOR: *Mis pedidos* → *Ver detalle* shows *Seguimiento del envío* (timeline and *Actualizar seguimiento*). As VENDEDOR: *Pedidos recibidos*, filters *En transporte / Entregados / Retornados*, same section in the detail. Returns: the *Seguimiento de devolución* box at the bottom of both panels (type 9001).
4. By hand: `LOGISTICS_WEBHOOK_SECRET=... ./scripts/cu24-25-demo-events.sh shipment <order> PICKED_UP` (also `IN_TRANSIT`, `DELIVERY_EXCEPTION`, `DELIVERY_ATTEMPT_FAILED`, `NEXT_ATTEMPT_SCHEDULED`, `DELIVERED`, `RETURNED_TO_SELLER`) and `... return 9001 PICKED_UP` (also `PICKUP_FAILED`, `INCIDENT`, `IN_TRANSIT`, `DELIVERED_TO_SELLER`). Resend the same event id (4th argument) to see `DUPLICATE`, send an earlier state after a later one to see `OUT_OF_ORDER`, three `PICKUP_FAILED` stop the return. Provider outage: `LOGISTICS_SIMULATED_MODE=UNAVAILABLE` (the UI keeps the last known tracking).

## Frontend architecture

Standalone-component app bootstrapped in `src/main.ts` (`provideIonicAngular`, `provideHttpClient` with `sessionInterceptor`, service worker only outside dev mode). Code is organised by feature folder (`carrito`, `catalogo`, `checkout`, `cuenta`, `pedidos`, `panel-vendedor`, `panel-admin-soporte`, `core`, …), each with `models/`, `services/` and sometimes `components/`.

- Talking to the backend: `carrito`, `catalogo`, the `checkout` services, session auth (`core/services/auth.service.ts` + `core/interceptors/session.interceptor.ts`: `withCredentials` and the CSRF header on writes; login UI in `core/components/acceso.component.ts`), the support moderation queue (`panel-admin-soporte`) and the seller orders panel (`panel-vendedor/components/pedidos-recibidos.component.ts`, CU-23, `/api/seller/orders` with the provisional `X-Store-Id` from `TIENDA_PROVISIONAL_ID`). The other feature services (`cuenta`, `mensajeria`, `pedidos`, `panel-*` except the two above, `reclamaciones-devoluciones`, `resenas-favoritos`) may still return typed **mocks**; check before assuming.
- Role-gated UI in `app.component.html`/`app.component.ts` follows the active role from `AuthService` (`esSoporte`, `esVendedor`, `esComprador`); the backend enforces permissions regardless. Buyer "Mis pedidos" (`pedidos/`) and seller "Pedidos recibidos" (`panel-vendedor/`) use different flags (`mostrarPedidos`, `mostrarPedidosRecibidos`); order status labels for the buyer live in `ETIQUETA_ESTADO_PEDIDO` (`pedidos/models/pedido.model.ts`) and must cover every `OrderStatus`. Products are fetched when the page opens, before login, so after logging in the page must be reloaded to see them.
- Services call the same-origin `API_BASE` (`/api`, `core/config/api.config.ts`); `npm start` proxies it to `http://localhost:8080` (`proxy.conf.json`).
- Logistics tracking lives in `seguimiento/`: `app-seguimiento-logistico` (orders and returns, buyer and seller; it only reads, re-reads every 10 s while the shipment is active and *Actualizar seguimiento* asks the backend to poll) and `app-consulta-devolucion` (look a return up by number until CU-19 provides a list), both embedded in *Mis pedidos* and *Pedidos recibidos*; `SeguimientoService` sends the provisional `X-Store-Id` for seller calls.
- There is no Angular router; UI and checkout orchestration live in the large `app/app.component.ts`.
