# ContractNoteManager – Web Services Architecture Plan

## 1. Goals

1. **Ingest** data from an external API.
2. **Persist** it in a relational database.
3. **Modify** the stored data (create / update / delete, with validation).
4. **Display and edit** everything in a web UI built from a supplied screen mockup.

Constraints: Java backend, JavaScript frontend, relational database.

---

## 2. Proposed Technology Stack

| Layer | Choice | Why |
|---|---|---|
| Backend language | **Java 21 (LTS)** | Current LTS, records, pattern matching, virtual threads |
| Backend framework | **Spring Boot 3.x** | De-facto standard; REST, scheduling, validation, security, JPA out of the box |
| External API client | Spring `RestClient` (+ Resilience4j retry / circuit breaker) | Simple synchronous client with robust failure handling |
| Persistence | Spring Data JPA (Hibernate) | Little boilerplate for CRUD; native SQL possible where needed |
| Database | **PostgreSQL 16** | Robust, free, strong JSON + constraint support. (MySQL/MariaDB or SQL Server are drop-in alternatives) |
| Schema migrations | **Flyway** | Versioned, reviewable SQL migrations |
| API documentation | springdoc-openapi (Swagger UI) | Auto-generated OpenAPI spec; frontend client can be generated from it |
| Frontend | **React 18 + TypeScript + Vite** | Large ecosystem, fast dev loop; TypeScript is JavaScript with type safety (plain JS also possible) |
| UI components | MUI or Ant Design (chosen once the mockup is known) | Ready-made data grids, forms, dialogs |
| Data fetching | TanStack Query + generated OpenAPI client | Caching, refetching, optimistic updates |
| Forms | React Hook Form + Zod | Client-side validation mirroring the backend |
| Build | Maven (backend), npm (frontend) | |
| Testing | JUnit 5, Mockito, Testcontainers (real Postgres), WireMock (fake external API); Vitest + React Testing Library, Playwright (E2E) | |
| Packaging / run | Docker + Docker Compose | One command starts DB, backend and frontend |
| CI | GitHub Actions | Build, test, lint on every push / PR |

---

## 3. High-Level Architecture

```
                 ┌─────────────────────────┐
                 │   External Data API     │
                 └────────────┬────────────┘
                              │ HTTPS (pull: scheduled or on demand)
┌─────────────────────────────▼──────────────────────────────────┐
│                  Backend – Spring Boot (Java)                  │
│                                                                │
│  ┌──────────────┐   ┌────────────────┐   ┌──────────────────┐  │
│  │ Integration  │──▶│ Import/Sync    │──▶│ Domain Services  │  │
│  │ (API client, │   │ Service (map,  │   │ (business rules, │  │
│  │  DTOs, retry)│   │ upsert, log)   │   │  validation)     │  │
│  └──────────────┘   └────────────────┘   └────────┬─────────┘  │
│                                                   │            │
│  ┌──────────────────────┐              ┌──────────▼─────────┐  │
│  │ REST Controllers     │◀────────────▶│ Repositories (JPA) │  │
│  │ /api/v1/...  (JSON)  │              └──────────┬─────────┘  │
│  └──────────▲───────────┘                         │            │
└─────────────┼─────────────────────────────────────┼────────────┘
              │ JSON over HTTP                      │ JDBC
┌─────────────┴───────────┐              ┌──────────▼─────────┐
│ Frontend – React (JS/TS)│              │   PostgreSQL       │
│ List / Detail / Edit    │              │   (Flyway-managed) │
│ views from the mockup   │              └────────────────────┘
└─────────────────────────┘
```

**Key principle:** the UI never talks to the external API directly. The backend is the single
owner of the data; the external API is only a *source* for imports.

---

## 4. Backend Design

### 4.1 Module / package structure

```
backend/src/main/java/com/contractnotemanager/
├── config/          # Spring config, CORS, security, scheduling, OpenAPI
├── integration/     # External API client, its DTOs, mapper to domain
├── sync/            # Import orchestration, scheduling, sync-run logging
├── domain/          # JPA entities + enums
├── repository/      # Spring Data repositories
├── service/         # Business logic, validation, transactions
├── web/             # REST controllers, request/response DTOs, mappers
│   └── error/       # Global exception handler (RFC 7807 problem+json)
└── Application.java
```

DTOs are kept separate per layer (external DTO ≠ entity ≠ REST DTO), so changes in the
external API do not leak into the UI contract. MapStruct can be used for mapping.

### 4.2 Data ingestion (API → DB)

- **Trigger options:** scheduled (`@Scheduled`, e.g. every N minutes / nightly, configurable) **and**
  manual (`POST /api/v1/sync` button in the UI).
- **Flow:** fetch (with paging) → validate → map → **upsert** by the external ID → record result.
- **Idempotent:** each record keeps `external_id`; re-imports update instead of duplicating.
- **Incremental sync** if the API supports it (`modifiedSince`, cursor, ETag); otherwise full sync.
- **Resilience:** timeouts, retries with back-off, circuit breaker; a failed run never corrupts data
  (one transaction per page/batch).
- **Sync log:** a `sync_run` table stores start/end, status, counts (created/updated/skipped/failed)
  and error messages – visible in the UI.
- **Concurrency guard:** only one sync at a time (DB lock or ShedLock if multiple instances).

#### 4.2.1 Source API: Sharpfin orders endpoint

```
GET https://demo2.sharpfin.com/api/orders/paginated
    ?type=instrument&date_type=active
    &page=1&page_size=10&no_of_elements=0
    &sort_field=booked_date&sort_direction=asc
    &from_date=2026-10-07&to_date=2026-10-07
    &status=all&owner_key=all
    &active_orders=true&include_deleted=false
```

| Parameter | Value used | How the importer will use it |
|---|---|---|
| `type` | `instrument` | Fixed; configurable in case other order types are needed later |
| `date_type` | `active` | Which date `from_date`/`to_date` filter on |
| `page`, `page_size` | `1`, `10` | Loop pages until all are read; use a larger page size (e.g. 100–500) if the API allows it |
| `no_of_elements` | `0` | Sent as `0`; the response returns the real total |
| `sort_field`, `sort_direction` | `booked_date`, `asc` | Keep a stable sort so records don't move between pages while paging |
| `from_date`, `to_date` | today | Default: import a configurable window (e.g. today, or last N days) and allow a manual date range from the UI |
| `status`, `owner_key` | `all` | Import everything; filtering happens in our UI |
| `active_orders` | `true` | Configurable |
| `include_deleted` | `false` | Consider `true` so orders deleted at the source can be marked deleted locally |

**Response envelope** (from a sample response):

```json
{ "no_of_pages": 2, "no_of_elements": 19, "page_size": 10, "page": 1,
  "sort_field": "booked_date", "sort_direction": "asc", "no_of_updatable": 19,
  "orders": [ { ...order... } ] }
```

Paging algorithm: request `page=1`, read `no_of_pages`, then fetch pages `2..no_of_pages`.
After the loop, check that the number of orders received equals `no_of_elements`, and mark the
run `PARTIAL` if it doesn't. Orders can be added while the import is paging, so orders are
upserted by `key`, which makes it harmless to see one twice.

**Observations about the payload that shape the design:**

| Observation | Consequence |
|---|---|
| All IDs are opaque `key` strings (Base64 of a 64-bit integer, e.g. `LTU3MDIz…` = `-570235573997296708`) | Store as `VARCHAR(64)` and never decode or interpret them; our tables use their own surrogate `id` |
| Amounts and prices are **strings** (`"435.52"`, `"-12109.86"`) | Parse to `BigDecimal` and store as `NUMERIC`, never `double` |
| Each order has a `version` (e.g. `1`) | Import change detection: update an order only when the remote `version` is higher than the one stored (see 4.3) |
| `custody`, `asset` and `owner` are full objects repeated in every order | Normalise them into reference tables, upserted by `key` |
| `allocation[].key` is the **portfolio** key (same key for "Kattegatt AB" in every order), not an allocation ID | An allocation is identified by order + portfolio |
| `allocation[].value` adds up to the order `value` (1761 + 103 = 1864) | Validation rule when allocations are edited |
| `type` = `quantity` → `value` is a number of units; `type` = `amount` → `value` is a cash amount | The UI labels and validates `value` according to `type` |
| `settlement_amount` ≈ `price × value`, positive for `sell` and negative for `buy` | Shown as a cash flow; can be recalculated (and checked) after edits |
| `asset.type` varies (`equity`, `fund`); funds carry extra `type_data` | Store the common columns and keep `type_data` as `JSONB` |
| `broker` and `exchange` are empty objects in the sample | Store as nullable `JSONB` until real data shows their structure |
| `custody.contract_notes_enabled` exists | Probably relevant for producing contract notes per custody (see open questions) |
| The payload contains personal data (owner name/email, client names in `portfolio_name`) | GDPR: restrict access, never log full payloads, and use only anonymised samples as test data |

Still needed: how the API authenticates (API key, bearer token, session cookie) and any rate limits.

### 4.3 Handling local modifications vs. re-imports (important decision)

Once users can edit imported data, a later import could overwrite their changes. Options:

| Strategy | Behaviour | Recommended when |
|---|---|---|
| **A. Local edits win** | Record gets `locally_modified = true`; sync skips (or only flags) these records | Edits are corrections the API will never reflect |
| B. API wins | Sync always overwrites | DB is merely a cache of the API |
| C. Field-level overrides | Store API value and user override separately; UI shows the effective value and the original | Full traceability is needed |

**Default proposal: A**, plus a "conflict" flag when the API value changed after a local edit,
so the user can review it in the UI. To be confirmed.

### 4.4 Data modification

- REST CRUD endpoints with Bean Validation (`@Valid`, `@NotNull`, …) and domain rules in services.
- **Optimistic locking** (`@Version` column) so two users can't silently overwrite each other
  (HTTP 409 on conflict).
- **Audit fields** on every table: `created_at`, `created_by`, `updated_at`, `updated_by`.
  Optional full change history via Hibernate Envers.
- Soft delete (`deleted_at`) for imported records, so a re-import does not resurrect deleted rows
  unintentionally.

### 4.5 REST API (draft)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/orders?page=&size=&sort=&bookedFrom=&bookedTo=&status=&side=&custody=&asset=&q=` | Paged, sortable, filterable order list |
| GET | `/api/v1/orders/{id}` | Order detail including allocations |
| PATCH | `/api/v1/orders/{id}` | Edit order fields (requires `version`) |
| PUT | `/api/v1/orders/{id}/allocations` | Replace allocations (validated: sum = order value) |
| DELETE | `/api/v1/orders/{id}` | Soft delete |
| POST | `/api/v1/orders/{id}/revert` | Discard local edits and restore the last imported values |
| GET | `/api/v1/custodies`, `/api/v1/assets`, `/api/v1/portfolios` | Reference data for filters and drop-downs |
| POST | `/api/v1/sync` (body: `fromDate`, `toDate`) | Trigger import now |
| GET | `/api/v1/sync/runs` | Import history and status |
| GET | `/actuator/health` | Health check |

Errors are returned as `application/problem+json`. The OpenAPI spec is published at
`/v3/api-docs` and used to generate the TypeScript client.

### 4.6 Data model

Derived from the sample response. Every table also gets the audit columns `created_at`,
`updated_at`, `created_by`, `updated_by` (left out below for brevity).

```
custody 1──* orders *──1 asset
owner   1──* orders
orders  1──* order_allocation *──1 portfolio
```

```sql
CREATE TABLE custody (
    id                     BIGSERIAL PRIMARY KEY,
    sf_key                 VARCHAR(64)  NOT NULL UNIQUE,   -- Sharpfin "key"
    name                   VARCHAR(200) NOT NULL,          -- "Nordnet"
    tag                    VARCHAR(100),
    domicile               CHAR(2),
    contract_notes_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted                BOOLEAN      NOT NULL DEFAULT FALSE,
    raw_payload            JSONB                           -- all remaining custody settings
);

CREATE TABLE asset (
    id                        BIGSERIAL PRIMARY KEY,
    sf_key                    VARCHAR(64)  NOT NULL UNIQUE,
    name                      VARCHAR(300) NOT NULL,       -- "ABB", "AMF Räntefond Lång"
    type                      VARCHAR(30)  NOT NULL,       -- equity | fund | ...
    isin                      VARCHAR(12),                 -- from identifiers[key=isin]
    domicile                  CHAR(2),
    quote_currency_code       CHAR(3),
    settlement_currency_code  CHAR(3),
    settlement_duration       INTEGER,                     -- T+n days
    classifications           JSONB,                       -- [{key, value}] e.g. "SWE-EQ", "it"
    type_data                 JSONB,                       -- fund-specific data
    deleted                   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE owner (                                       -- the Sharpfin user owning an order
    id        BIGSERIAL PRIMARY KEY,
    sf_key    VARCHAR(64)  NOT NULL UNIQUE,
    name      VARCHAR(200),
    email     VARCHAR(320),
    type      VARCHAR(50)                                  -- "system_user"
);

CREATE TABLE portfolio (
    id        BIGSERIAL PRIMARY KEY,
    sf_key    VARCHAR(64)  NOT NULL UNIQUE,                -- allocation[].key
    name      VARCHAR(300) NOT NULL                        -- "Kattegatt AB - 150660816"
);

CREATE TABLE orders (                                      -- "order" is a reserved word
    id                        BIGSERIAL PRIMARY KEY,
    sf_key                    VARCHAR(64)  NOT NULL UNIQUE,
    sf_version                INTEGER      NOT NULL,       -- remote "version", for change detection
    custody_id                BIGINT       NOT NULL REFERENCES custody(id),
    asset_id                  BIGINT       NOT NULL REFERENCES asset(id),
    owner_id                  BIGINT       REFERENCES owner(id),
    status                    VARCHAR(30)  NOT NULL,       -- new | ...
    order_type                VARCHAR(20)  NOT NULL,       -- quantity | amount
    side                      VARCHAR(10)  NOT NULL,       -- buy | sell
    price                     NUMERIC(24,8),
    value                     NUMERIC(24,8) NOT NULL,      -- units or cash depending on order_type
    currency_code             CHAR(3)      NOT NULL,
    settlement_amount         NUMERIC(24,8),
    commission                NUMERIC(24,8) NOT NULL DEFAULT 0,
    accrued_interest          NUMERIC(24,8) NOT NULL DEFAULT 0,
    up_front_fee              NUMERIC(24,8) NOT NULL DEFAULT 0,
    issuer_fee                NUMERIC(24,8) NOT NULL DEFAULT 0,
    booked_date               DATE         NOT NULL,
    valid_to                  DATE,
    source                    VARCHAR(30),                 -- rebalance | ...
    comment                   TEXT,
    partial_fill_allowed      BOOLEAN NOT NULL DEFAULT FALSE,
    is_merged                 BOOLEAN NOT NULL DEFAULT FALSE,
    handled_manually          BOOLEAN NOT NULL DEFAULT FALSE,
    handled_manually_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    broker                    JSONB,
    exchange                  JSONB,
    sf_deleted                BOOLEAN NOT NULL DEFAULT FALSE,
    -- local bookkeeping
    locally_modified          BOOLEAN NOT NULL DEFAULT FALSE,
    sync_conflict             BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at            TIMESTAMPTZ,
    raw_payload               JSONB,                       -- last imported order, used for "revert"
    version                   INTEGER NOT NULL DEFAULT 0,  -- optimistic locking for local edits
    deleted_at                TIMESTAMPTZ
);
CREATE INDEX ix_orders_booked_date ON orders(booked_date);
CREATE INDEX ix_orders_asset       ON orders(asset_id);

CREATE TABLE order_allocation (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    portfolio_id  BIGINT        NOT NULL REFERENCES portfolio(id),
    value         NUMERIC(24,8) NOT NULL,
    status        VARCHAR(30),
    reason        TEXT,
    external_id   VARCHAR(100),
    UNIQUE (order_id, portfolio_id)
);

CREATE TABLE sync_run (
    id             BIGSERIAL PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL,       -- RUNNING | SUCCESS | PARTIAL | FAILED
    trigger_type   VARCHAR(20) NOT NULL,       -- SCHEDULED | MANUAL
    from_date      DATE,
    to_date        DATE,
    expected_count INTEGER,                    -- no_of_elements reported by the API
    created_count  INTEGER DEFAULT 0,
    updated_count  INTEGER DEFAULT 0,
    skipped_count  INTEGER DEFAULT 0,
    conflict_count INTEGER DEFAULT 0,
    failed_count   INTEGER DEFAULT 0,
    error_message  TEXT
);
```

**Import rule per order** (strategy A from 4.3):

| Order state in DB | Remote `version` vs stored `sf_version` | Action |
|---|---|---|
| not present | – | insert order + allocations |
| present, not locally modified | higher | overwrite with remote values |
| present, locally modified | higher | keep local values, set `sync_conflict = true`, store new remote payload for comparison |
| present | same or lower | skip |

### 4.7 Security

- Phase 1: simple login (Spring Security, form/session or HTTP Basic) or none for local use.
- Later: OAuth2/OIDC (Keycloak, Azure AD, Google, …) with roles, e.g. `VIEWER` / `EDITOR` / `ADMIN`.
- External API credentials only via environment variables / secret store – never in git.
- CORS restricted to the frontend origin; HTTPS in production.

---

## 5. Frontend Design

```
frontend/src/
├── api/            # Generated OpenAPI client + TanStack Query hooks
├── components/     # Reusable UI pieces (table, form fields, dialogs)
├── pages/          # One folder per screen from the mockup
├── routes.tsx      # React Router
├── theme/          # Colours, typography matching the mockup
└── main.tsx
```

Expected screens (to be aligned with your mockup):

1. **List / overview** – data grid with paging, sorting, filtering, search; badges for
   "modified locally" / "sync conflict".
2. **Detail / edit** – form with validation, save/cancel, concurrency-conflict message.
3. **Create** – same form, empty.
4. **Sync status** – "Import now" button, last runs and their results.

**Mockup workflow:** once you share the mockup (image, Figma link or PDF), I will
(1) break it into components, (2) map every field to the data model / API,
(3) list any gaps or questions, then (4) implement it pixel-close.

---

## 6. Repository Layout & Runtime

```
ContractNoteManager/
├── backend/                 # Spring Boot (Maven)
├── frontend/                # React + Vite
├── docs/                    # Architecture, ADRs, mockups
├── docker-compose.yml       # postgres + backend + frontend
└── .github/workflows/ci.yml
```

- **Local dev:** `docker compose up db`, then `./mvnw spring-boot:run` and `npm run dev`
  (Vite proxies `/api` to the backend → no CORS issues).
- **Production options:** (a) one container – frontend built into Spring Boot's static resources;
  or (b) separate containers with Nginx serving the frontend and reverse-proxying `/api`.
- Configuration via Spring profiles (`dev`, `test`, `prod`) and environment variables.

---

## 7. Implementation Roadmap

| Phase | Deliverable | Done when |
|---|---|---|
| **0. Scaffolding** | Repo structure, Spring Boot + React skeletons, Docker Compose with Postgres, Flyway, CI pipeline | `docker compose up` shows a "hello" page fetching from `/api/v1/health` |
| **1. Data model** | Entities + Flyway migrations derived from the external API payload | Migrations run cleanly; repository tests green (Testcontainers) |
| **2. Ingestion** | API client, mapping, upsert, scheduled + manual sync, sync log | Import against WireMock and the real API populates the DB idempotently |
| **3. REST API** | CRUD endpoints, validation, paging/filtering, optimistic locking, error handling, OpenAPI | Endpoints covered by integration tests; Swagger UI usable |
| **4. UI from mockup** | List, detail/edit, create, sync status screens | Matches mockup; full flow works end-to-end |
| **5. Hardening** | Authentication/roles, audit history, logging/metrics (Actuator), E2E tests (Playwright) | Production-ready checklist passed |
| **6. Deployment** | Production Docker image(s), environment config, DB backups | Running on the target environment |

Phases 2 and 3 can be developed in parallel; phase 4 can start against mocked API responses
as soon as the OpenAPI contract of phase 3 is agreed.

---

## 8. Open Questions (needed before / during Phase 1)

1. **External API:** endpoint and response are known (see 4.2.1). Still open: authentication method and rate limits.
2. **Sync frequency:** on demand only, scheduled (how often), or both? Data volume (records per run)?
3. **Edit vs. re-import conflict strategy:** A, B or C from section 4.3?
4. **Database:** is PostgreSQL fine, or is there an existing company DB (SQL Server, Oracle, MySQL)?
5. **Users & security:** single user or multiple users/roles? Existing identity provider (SSO)?
6. **Hosting target:** on-premise server, cloud (AWS/Azure/GCP), Kubernetes, or local only?
7. **Frontend language:** TypeScript (recommended) or plain JavaScript?
8. **Mockup:** please share it (image/PDF/Figma) – it determines screens and component library.
9. **Write-back:** should changes ever be pushed back to the external API, or is the DB the end of the line?
10. **Which fields are editable?** E.g. price, commission, fees, allocations, comment, status – or everything?
11. **Contract notes:** given the project name and `custody.contract_notes_enabled`, should the app
    later *produce* contract notes (e.g. one PDF per order allocation / portfolio)? That would add a
    `contract_note` table and a document generator to the plan.
