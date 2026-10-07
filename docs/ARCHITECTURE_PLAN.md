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
| `no_of_elements` | `0` | Probably a total-count hint. Send `0` on the first call and read the total from the response (to be confirmed) |
| `sort_field`, `sort_direction` | `booked_date`, `asc` | Keep a stable sort so records don't move between pages while paging |
| `from_date`, `to_date` | today | Default: import a configurable window (e.g. today, or last N days) and allow a manual date range from the UI |
| `status`, `owner_key` | `all` | Import everything; filtering happens in our UI |
| `active_orders` | `true` | Configurable |
| `include_deleted` | `false` | Consider `true` so orders deleted at the source can be marked deleted locally |

The main stored entity is therefore an **order** (instrument order). The table in 4.6 becomes
`orders`, keyed by the Sharpfin order ID, with columns taken from the response payload.
Still needed: a sample response (field names and types, where the total count is), and how the
API authenticates (API key, bearer token, session cookie).

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

### 4.5 REST API (draft – finalised once the data model is known)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/contract-notes?page=&size=&sort=&filter=` | Paged, sortable, filterable list |
| GET | `/api/v1/contract-notes/{id}` | Detail |
| POST | `/api/v1/contract-notes` | Create manually |
| PUT / PATCH | `/api/v1/contract-notes/{id}` | Update (requires version) |
| DELETE | `/api/v1/contract-notes/{id}` | (Soft) delete |
| POST | `/api/v1/sync` | Trigger import now |
| GET | `/api/v1/sync/runs` | Import history and status |
| GET | `/actuator/health` | Health check |

Errors are returned as `application/problem+json`. The OpenAPI spec is published at
`/v3/api-docs` and used to generate the TypeScript client.

### 4.6 Data model (placeholder)

The real entities depend on the external API's payload. Starting skeleton:

```sql
CREATE TABLE contract_note (
    id               BIGSERIAL PRIMARY KEY,
    external_id      VARCHAR(100) UNIQUE,      -- NULL for manually created records
    -- business columns derived from the API payload go here
    source           VARCHAR(20)  NOT NULL,    -- 'API' | 'MANUAL'
    locally_modified BOOLEAN      NOT NULL DEFAULT FALSE,
    sync_conflict    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_synced_at   TIMESTAMPTZ,
    raw_payload      JSONB,                    -- original API record, for traceability/debugging
    version          INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       VARCHAR(100),
    deleted_at       TIMESTAMPTZ
);

CREATE TABLE sync_run (
    id             BIGSERIAL PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL,       -- RUNNING | SUCCESS | PARTIAL | FAILED
    trigger_type   VARCHAR(20) NOT NULL,       -- SCHEDULED | MANUAL
    created_count  INTEGER DEFAULT 0,
    updated_count  INTEGER DEFAULT 0,
    skipped_count  INTEGER DEFAULT 0,
    failed_count   INTEGER DEFAULT 0,
    error_message  TEXT
);
```

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

1. **External API:** endpoint known (see 4.2.1). Still open: sample response payload, authentication method, rate limits.
2. **Sync frequency:** on demand only, scheduled (how often), or both? Data volume (records per run)?
3. **Edit vs. re-import conflict strategy:** A, B or C from section 4.3?
4. **Database:** is PostgreSQL fine, or is there an existing company DB (SQL Server, Oracle, MySQL)?
5. **Users & security:** single user or multiple users/roles? Existing identity provider (SSO)?
6. **Hosting target:** on-premise server, cloud (AWS/Azure/GCP), Kubernetes, or local only?
7. **Frontend language:** TypeScript (recommended) or plain JavaScript?
8. **Mockup:** please share it (image/PDF/Figma) – it determines screens and component library.
9. **Write-back:** should changes ever be pushed back to the external API, or is the DB the end of the line?
