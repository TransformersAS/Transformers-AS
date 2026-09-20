# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Monorepo for a university marketplace project (team "Transformers", Javeriana). Docs and code comments are mostly in Spanish.

- `backend/demo/` — Spring Boot 4.1.1 / Java 21 modular monolith (Maven, MySQL 8.4.11, Flyway). The folder name `demo` is kept for Docker/CI paths; the Maven artifact is `marketplace-backend` and the base package is `com.transformersas.marketplace`.
- `frontend/` — Ionic 8 + Angular 18 standalone app (PWA, Capacitor-ready). Web is the only official target.
- `compose.yaml` — local dev (MySQL + backend + nginx frontend on :4300). `stack.yml` + `deploy.sh` — Docker Swarm demo deployment (see `docs/swarm.md`).
- `scripts/cu23-demo-seed.sh` — dev-only demo data (seller/buyer accounts, orders in several states); see "Trying CU-23 from the UI" below.
- `docs/` — `Decisiones_Arquitectonicas_Marketplace.md` (ADR for the course), `contracts/` (external logistics/notifications provider APIs), `testing/coverage.md` (coverage policy). There is no CU-23 integration/test guide in the repo: its essentials (seller endpoints and error codes, how to try it from the UI) are in this file.

## Commands

### Backend (run from `backend/demo`; use the wrapper, JDK 21 required)

```bash
./mvnw clean verify                 # full build + tests + JaCoCo gate (Windows: mvnw.cmd)
./mvnw test -Dtest=BusinessApiIntegrationTests                                  # one test class
./mvnw test -Dtest=BusinessApiIntegrationTests#cartLifecyclePersistsAndCalculatesTotals   # one test method
```

Tests are `@SpringBootTest` + Testcontainers (`mysql:8.4.11`), so **Docker must be running**; they need no `.env` or Compose. Reports: `target/site/jacoco/index.html`, `target/surefire-reports/`.

- **Coverage gate**: `verify` runs `jacoco:check` and fails below **95 % global line coverage** (`docs/testing/coverage.md`; CI is `.github/workflows/backend-ci.yml`). Use `mvnw test -Dtest=...` for subsets: a partial run through `verify` passes its tests but ends in BUILD FAILURE on the gate.
- The suite has ~589 tests and several Spring contexts/containers, so a full `verify` takes tens of minutes. CU-23 tests extend `support/AbstractIntegrationTest` (one shared MySQL container, real session login, `performAsSeller`, seeding helpers, per-test cleanup: add new tables to its `TABLES_TO_CLEAR` in FK order); older suites (`OrderOwnershipIntegrationTests`, `SupportApiIntegrationTests`, …) bring their own `@Container` and their own cleanup lists.
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

`backend/demo/ARCHITECTURE.md` prescribes a per-module hexagonal layout (`domain/{model,repository}`, `application/{dto,usecase}`, `infrastructure/{persistence,web}`) with rules such as "controllers call one use case", "domain models are not JPA entities", and "a module never touches another module's entities or repositories". `orders`, `payments`, `logistics`, `notifications`, `inventory`, `users`, `auth` and `reports` (CU-21 moderation) follow it; newer modules use `JdbcClient` adapters for append-only tables instead of JPA entities. `catalog`, `returns` and `reviews` are still empty skeletons containing only `package-info.java`.

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

- **Order states** (`OrderStatus`, 11 values): `CONFIRMED`, `IN_PREPARATION`, `READY_FOR_DISPATCH`, the logistics states reserved for CU-24, `CANCELLED`, and `CANCELLATION_REQUESTED`. Only four transitions exist (`CONFIRMED → IN_PREPARATION | CANCELLED`, `IN_PREPARATION → READY_FOR_DISPATCH | CANCELLED`), defined in `OrderStatus.allowedTargets()`.
- **Buyer** (`/api/orders`, role `COMPRADOR`): list/detail of own orders (`FindOwnOrders`) and `POST /{id}/cancellation` (`RequestOrderCancellation`), which only moves `CONFIRMED → CANCELLATION_REQUESTED`. Nothing resolves that state yet (CU-11 pending), and that request writes no history or audit.
- **Seller** (`/api/seller/orders`, needs the `VENDEDOR` active role + `X-Store-Id`): list (filters `status`, `from`, `to`, `orderId`, `page`, `size`; default `CONFIRMED`+`IN_PREPARATION`), detail, `POST /{id}/start-preparation`, `/issues`, `/issues/{issueId}/resolve`, `/ready-for-dispatch`, `/shipment` (manual retry: 201 created now, 200 already existed) and `/cancel` (`reasonCode` `OUT_OF_STOCK|PRODUCT_DAMAGED|OTHER`, `OTHER` needs details). Stable error codes: 400 `INVALID_PAGINATION`, `INVALID_DATE_RANGE`, `CANCELLATION_DETAILS_REQUIRED`; 401 `UNAUTHENTICATED`, `STORE_IDENTITY_MISSING`; 403 `SELLER_ROLE_REQUIRED`; 404 `ORDER_NOT_FOUND`, `ISSUE_NOT_FOUND` (another store's order is a 404, never a 403); 409 `ORDER_INVALID_TRANSITION`, `PAYMENT_NOT_APPROVED`, `INVENTORY_INCONSISTENT` (also opens an issue; inconsistent = product missing or negative stock, since stock is already decremented at payment), `ORDER_STATE_CONFLICT`, `OPEN_ISSUES`, `ISSUE_ALREADY_OPEN`, `ISSUE_ALREADY_RESOLVED`, `ISSUE_NOT_ALLOWED_IN_STATUS`, `ORDER_NOT_READY_FOR_DISPATCH`, `ORDER_HAS_SHIPMENT`, `ORDER_ALREADY_CANCELLED`, `MULTI_STORE_CART`; 502 `SHIPMENT_PROVIDER_FAILED`. `ready-for-dispatch` returns 200 even if the provider fails (`shipment.status` `FAILED`; the order stays ready and the shipment is retried manually). CU-11 (buyer) should reuse `orders.CancelOrderUseCase` with initiator `BUYER` after checking ownership.

Rules that apply to anything touching orders:

- **State changes are compare-and-set** (`OrderRepository.transitionStatus(id, from, to)` = `UPDATE ... WHERE status = :from`); history, audit and notifications are written only if it affected a row, all in the same transaction.
- **External calls never run inside a DB transaction**: provider calls (logistics, external notices, refunds) happen after the commit, with a ≤10 s timeout (`shared/http/ExternalRestClients`), a Resilience4j breaker per integration, and no automatic retries. Providers have `simulated` (default) and `http` adapters (`logistics.provider`, `notifications.provider`); simulated failure modes come from `logistics.simulated.mode`, `notifications.simulated.mode` and `payments.refund.simulated.mode` (also settable as env vars).
- `OrderMapper.newEntity` only inserts; never use it to update an order. Orders carry `store_id` and an immutable `delivery_*` snapshot copied at purchase — read that, never `addresses`. The buyer is `orders.account_id` (`NULL` only for historical orders).
- Cross-module calls go through the other module's use case (`GetStockLevelsUseCase`, `RestoreStockUseCase`, `RequestRefundUseCase`, `PublishNotificationUseCase` (`MANDATORY` tx), `CreateShipmentUseCase`).
- Errors with a stable `code` use `shared/error/BusinessException`; request DTOs for actions implement `StrictJsonRequest` so unknown properties return 400.
- Every request gets an `X-Correlation-Id` (`CorrelationIdFilter`) that is propagated to history, audit, notifications, refunds and provider calls.

### Known simplifications to keep in mind

- **Auth is session-based (CU-08)**: `/api/**` needs a session (cookie `SESSION`), CSRF is enabled (`/api/auth/csrf`), roles are `COMPRADOR|VENDEDOR|ADMIN|SOPORTE` with one active role. Password change and recovery live under `/api/auth`. `SecurityConfiguration` gates `/api/support/**` to `SOPORTE` and `/api/orders/**` to `COMPRADOR`; seller endpoints only require a session and check the `VENDEDOR` active role in `SessionSellerActorProvider`. The seller's **store is provisional** (`X-Store-Id` header, not checked against the account) until CU-18 defines account-store ownership.
- There is still a single global cart (`cartRepository.findAll().stream().findFirst()`), shared by all buyers.
- CORS is configured globally in `SecurityConfiguration` (`app.cors.allowed-origins`, credentials allowed); six legacy controllers still carry `@CrossOrigin(origins = "*")`.
- Legacy errors are `ResponseStatusException` rendered by `shared/ApiExceptionHandler` as `{status, error, message, path}`; `BusinessException` adds `code` and optional `details`.
- `payments` has two `PaymentRequest`/`PaymentResponse` pairs (application DTO vs. web request/response); the web layer maps between them. `PaymentGateway` and `RefundGateway` only have simulated adapters.
- Spring Cache/Caffeine (`@EnableCaching`) is on the classpath but unused — no `@Cacheable`. Resilience4j breakers exist only for `logistics` and `notifications`.
- No read endpoints exist for notifications or refunds; `payment_status` is not reconciled if a pending refund completes later.

### Database

- Flyway owns the schema (`src/main/resources/db/migration/`, currently V1–V19): V1–V5 core (products, carts, addresses, reservations, orders), V6–V7 accounts and JDBC sessions, V8–V9 moderation (reports, cases), V10 password-recovery tokens, V11–V12 `orders.account_id` and wider `status`, V13–V19 CU-23 (stores/snapshot, status history, `audit_events`, shipments, notifications, order issues, cancellations/refunds). Hibernate runs with `ddl-auto=validate`, so every entity change needs a **new** migration numbered after the highest one on `origin/main`; never edit an applied one and never use `update`/`create`. Another branch may have taken a number: renumber yours when merging (`feature/cu02-recommendations` will need to move its V8).
- Tests build the schema through the same Flyway migrations against a fresh Testcontainers MySQL. Suites that wipe business tables must include the child tables of `orders` (`order_status_history`, `shipments`, `order_issues`, `order_cancellations`, `refunds`, `notifications`, `audit_events`) in FK order, and raw `INSERT INTO orders` needs `store_id` and the `delivery_*` columns (`NOT NULL`, no default).
- Actuator exposes only `health`; the readiness group includes `db`, the Docker healthcheck uses liveness.

### Stale docs

`README.md` (root) and `db/migration/README.md` still say there are no use cases and no V1 migration; that predates the cart/checkout/payment work (schema is at V19). `docs/testing/coverage.md` quotes 248 tests and 99.68 % line coverage from an earlier stage. Trust the code and migrations over those statements. The rest of the README (setup, team rules) is still valid: branch per change (never commit to `main`), merge `origin/main` and run `./mvnw clean verify` before integrating.

### Trying CU-23 from the UI

1. `.env` with `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD` and `SPRING_PROFILES_ACTIVE=local`; then `docker compose up -d --build --wait` and open `http://localhost:4300`.
2. Log in with `demo@marketplace.local` / `MarketplaceDemo123!` (created only by the `local` profile), pick the active role **COMPRADOR** in the account panel, and **reload the page** (products are fetched before login, so they only appear after a reload).
3. Add "Camiseta demo local" → cart → *Continuar compra* → address → *Calcular total* → *Confirmar compra* (`CARD` is approved).
4. Switch the active role to **VENDEDOR**: the *Pedidos recibidos* button appears. Prepare, register/resolve issues, mark ready for dispatch or cancel. Back as COMPRADOR, *Mis pedidos* shows the resulting state.
5. Extra cases: `scripts/cu23-demo-seed.sh` (env `MYSQL_CONTAINER`, `DB_PASSWORD`, `DEMO_PASSWORD`; repeatable) creates `vendedor.demo@example.com` / `comprador.demo@example.com` and six orders, including one whose product no longer exists (inventory inconsistency) and one ready for dispatch without a shipment (retry). Provider failures: a local, uncommitted `compose.override.yaml` setting `LOGISTICS_SIMULATED_MODE`, `PAYMENTS_REFUND_SIMULATED_MODE` or `NOTIFICATIONS_SIMULATED_MODE`. Notifications, refunds and audit are only visible in the DB (`docker compose exec -T mysql sh -c 'mysql -umarketplace_app -p$MYSQL_PASSWORD marketplace'`, SQL on stdin).

## Frontend architecture

Standalone-component app bootstrapped in `src/main.ts` (`provideIonicAngular`, `provideHttpClient` with `sessionInterceptor`, service worker only outside dev mode). Code is organised by feature folder (`carrito`, `catalogo`, `checkout`, `cuenta`, `pedidos`, `panel-vendedor`, `panel-admin-soporte`, `core`, …), each with `models/`, `services/` and sometimes `components/`.

- Talking to the backend: `carrito`, `catalogo`, the `checkout` services, session auth (`core/services/auth.service.ts` + `core/interceptors/session.interceptor.ts`: `withCredentials` and the CSRF header on writes; login UI in `core/components/acceso.component.ts`), the support moderation queue (`panel-admin-soporte`) and the seller orders panel (`panel-vendedor/components/pedidos-recibidos.component.ts`, CU-23, `/api/seller/orders` with the provisional `X-Store-Id` from `TIENDA_PROVISIONAL_ID`). The other feature services (`cuenta`, `mensajeria`, `pedidos`, `panel-*` except the two above, `reclamaciones-devoluciones`, `resenas-favoritos`) may still return typed **mocks**; check before assuming.
- Role-gated UI in `app.component.html`/`app.component.ts` follows the active role from `AuthService` (`esSoporte`, `esVendedor`, `esComprador`); the backend enforces permissions regardless. Buyer "Mis pedidos" (`pedidos/`) and seller "Pedidos recibidos" (`panel-vendedor/`) use different flags (`mostrarPedidos`, `mostrarPedidosRecibidos`); order status labels for the buyer live in `ETIQUETA_ESTADO_PEDIDO` (`pedidos/models/pedido.model.ts`) and must cover every `OrderStatus`. Products are fetched when the page opens, before login, so after logging in the page must be reloaded to see them.
- Services call the same-origin `API_BASE` (`/api`, `core/config/api.config.ts`); `npm start` proxies it to `http://localhost:8080` (`proxy.conf.json`).
- There is no Angular router; UI and checkout orchestration live in the large `app/app.component.ts`.
