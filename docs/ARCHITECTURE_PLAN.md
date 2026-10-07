# ContractNoteManager – Web Services Architecture Plan

## 1. Goals

1. **Import** instrument orders from the Sharpfin orders API when the user clicks a button.
2. **Persist** them in a relational database.
3. **Modify** the stored orders (edit, delete, revert to the imported values, with validation).
4. **Display and edit** everything in a web UI.

**Context and decisions so far**

| Topic | Decision |
|---|---|
| Languages | Java backend, **plain JavaScript** frontend |
| Database | **PostgreSQL** |
| Users | **One user**, no login |
| Where it runs | **Locally on a Mac** |
| Import trigger | **Manual only**: one run per click of an "Import" button |
| Screen mockup | Not decided yet. A sensible default UI is built first and can be restyled to a mockup later |

---

## 2. Technology Stack

| Layer | Choice | Why |
|---|---|---|
| Backend language | **Java 21 (LTS)** | Current long-term-support Java |
| Backend framework | **Spring Boot 3.x** | REST, validation, JPA and configuration out of the box |
| External API client | Spring `RestClient` with timeouts and a simple retry | Enough for a manual, on-click import |
| Persistence | Spring Data JPA (Hibernate) | Little boilerplate for CRUD |
| Database | **PostgreSQL 16** | Free, robust, good `NUMERIC` and `JSONB` support |
| Schema migrations | **Flyway** | Versioned SQL scripts, applied automatically at startup |
| API documentation | springdoc-openapi (Swagger UI) | Browse and try the backend API at `/swagger-ui.html` |
| Frontend | **React 18 + Vite, plain JavaScript** (`.jsx`) | Large ecosystem, fast dev loop, no TypeScript |
| UI components | **MUI** (Material UI) incl. the free MUI X DataGrid | Ready-made table with sorting/filtering, forms, dialogs |
| Data fetching | TanStack Query + a small `fetch` wrapper | Caching and automatic refresh after edits |
| Forms | React Hook Form | Simple form state and validation |
| Build | Maven (backend, also builds the frontend into the JAR), npm (frontend) | |
| Testing | JUnit 5, Testcontainers (real Postgres), WireMock (fake Sharpfin API); Vitest + React Testing Library | |

**Left out on purpose** for a single-user local app: scheduled imports, login/roles, multi-instance
locking, Kubernetes/cloud deployment, Nginx. Any of these can be added later without redesign.

---

## 3. High-Level Architecture

```
                 ┌─────────────────────────────┐
                 │  Sharpfin API (demo2…)      │
                 └──────────────┬──────────────┘
                                │ HTTPS, only when "Import" is clicked
┌───────────────────────────────▼──────────────────────────────────┐
│           Backend – Spring Boot (Java), localhost:8080           │
│                                                                  │
│  ┌──────────────┐   ┌────────────────┐   ┌────────────────────┐  │
│  │ Integration  │──▶│ Import service │──▶│ Order service      │  │
│  │ (API client, │   │ (paging, map,  │   │ (validation, edit, │  │
│  │  DTOs)       │   │ upsert, log)   │   │  revert)           │  │
│  └──────────────┘   └────────────────┘   └─────────┬──────────┘  │
│                                                    │             │
│  ┌──────────────────────┐               ┌──────────▼──────────┐  │
│  │ REST controllers     │◀─────────────▶│ Repositories (JPA)  │  │
│  │ /api/v1/...  (JSON)  │               └──────────┬──────────┘  │
│  │ + serves the built UI│                          │             │
│  └──────────▲───────────┘                          │             │
└─────────────┼──────────────────────────────────────┼─────────────┘
              │ JSON over HTTP                       │ JDBC
┌─────────────┴────────────┐              ┌──────────▼──────────┐
│ Browser – React (JS)     │              │ PostgreSQL 16       │
│ Orders list, edit, import│              │ (Docker on the Mac) │
└──────────────────────────┘              └─────────────────────┘
```

The UI never calls Sharpfin directly. The backend owns the data; Sharpfin is only the source
for imports.

---

## 4. Backend Design

### 4.1 Package structure

```
backend/src/main/java/com/contractnotemanager/
├── config/          # Spring config, Sharpfin connection settings
├── integration/     # Sharpfin API client, its DTOs, mapping to entities
├── importer/        # Import orchestration (paging, upsert) and import-run log
├── domain/          # JPA entities + enums
├── repository/      # Spring Data repositories
├── service/         # Business logic, validation, transactions
├── web/             # REST controllers, request/response DTOs
│   └── error/       # Global exception handler (problem+json)
└── Application.java
```

Sharpfin DTOs, database entities and REST DTOs are separate classes, so a change in the Sharpfin
API does not leak into the UI.

### 4.2 Import (API → DB)

- **Trigger:** only manual. The UI's "Import" button opens a dialog with a date range
  (default: today) and calls `POST /api/v1/imports`.
- **Flow:** fetch all pages → map → **upsert** by Sharpfin `key` → write an `import_run` record.
- **Idempotent:** re-importing the same day updates orders instead of duplicating them.
- **Safe:** timeouts and a short retry on network errors. Each page is saved in its own
  transaction, and the run is marked `FAILED` or `PARTIAL` with the error message.
- **One at a time:** a second click while an import runs is rejected (HTTP 409). The button
  is disabled in the UI while an import runs.
- **Feedback:** for the expected volumes (tens to hundreds of orders) the request waits until the
  import finishes and returns the counts (created / updated / skipped / conflicts). Very large
  imports could later run in the background with a progress indicator.

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
| `from_date`, `to_date` | today | Chosen in the import dialog (defaults to today) |
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

### 4.3 Local edits vs. re-imports

Because imports are manual, the rule can stay simple: **local edits win**.

- Editing an order sets `locally_modified = true`.
- A later import does **not** overwrite a locally modified order. If Sharpfin's `version` has
  gone up since, the order is flagged with `sync_conflict = true` and shown with a badge, so
  the change can be checked.
- **"Revert"** on an order discards the local edits and restores the last imported values.

### 4.4 Modifying data

- REST endpoints with Bean Validation and business rules in the service layer, e.g.
  allocations must add up to the order `value`, and amounts must be valid decimals.
- An `@Version` column prevents saving stale data, e.g. when the same order is edited in two
  browser tabs (HTTP 409 and a "reload" message).
- **Delete** is a soft delete (`deleted_at`), so a re-import does not bring the order back.
  Deleted orders can be shown and restored with a filter.
- Optional later: a change history per order (Hibernate Envers).

### 4.5 REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/orders?page=&size=&sort=&bookedFrom=&bookedTo=&status=&side=&custody=&asset=&q=` | Paged, sortable, filterable order list |
| GET | `/api/v1/orders/{id}` | Order detail including allocations |
| PATCH | `/api/v1/orders/{id}` | Edit order fields (requires `version`) |
| PUT | `/api/v1/orders/{id}/allocations` | Replace allocations (validated: sum = order value) |
| DELETE | `/api/v1/orders/{id}` | Soft delete |
| POST | `/api/v1/orders/{id}/revert` | Discard local edits and restore the last imported values |
| GET | `/api/v1/custodies`, `/api/v1/assets`, `/api/v1/portfolios` | Reference data for filters and drop-downs |
| POST | `/api/v1/imports` (body: `fromDate`, `toDate`) | Run an import now |
| GET | `/api/v1/imports` | Import history |

Errors are returned as `application/problem+json`.

### 4.6 Data model

Derived from the sample response. Every table also gets `created_at` and `updated_at` columns
(left out below for brevity). There is one user, so no `created_by`/`updated_by`.

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

**Import rule per order** (see 4.3):

| Order state in DB | Remote `version` vs stored `sf_version` | Action |
|---|---|---|
| not present | – | insert order + allocations |
| present, not locally modified | higher | overwrite with remote values |
| present, locally modified | higher | keep local values, set `sync_conflict = true`, store new remote payload for comparison |
| present | same or lower | skip |

### 4.7 Security (local single-user app)

- No login. The backend listens on `127.0.0.1` only, so the app isn't reachable from other
  machines on the network.
- The Sharpfin credentials live in an environment variable or in a git-ignored
  `application-local.yml`, never in git.
- The database contains personal data (client names in portfolios, owner email). It stays on the
  Mac, the database password is local-only, and full API payloads are not written to log files.

---

## 5. Frontend Design (plain JavaScript)

```
frontend/src/
├── api/            # fetch wrapper + TanStack Query hooks (useOrders, useImport, …)
├── components/     # Reusable pieces (money/quantity cells, status badges, dialogs)
├── pages/
│   ├── OrdersPage.jsx       # list + filters + import button
│   ├── OrderDetailPage.jsx  # view/edit order and allocations
│   └── ImportsPage.jsx      # import history
├── App.jsx         # layout + React Router routes
└── main.jsx
```

**Default screens** (built first; restyled if a mockup arrives):

1. **Orders**: a table with booked date, side (buy/sell), asset name and ISIN, custody, type,
   value, price, currency, settlement amount and status. It has filters for date range, side,
   custody and status, plus a search box. Badges show "edited" and "conflict". An
   **Import** button opens the date-range dialog and shows the result counts.
2. **Order detail / edit**: editable order fields and an allocations table (portfolio, value)
   with a running total that must equal the order value. It has Save, Cancel, Revert and Delete.
3. **Import history**: the past runs with time, date range, status, counts and errors.

If a mockup arrives later (image, PDF or Figma), it is mapped onto these components; the backend
does not change.

---

## 6. Repository Layout & Running on the Mac

```
ContractNoteManager/
├── backend/                 # Spring Boot (Maven wrapper ./mvnw)
├── frontend/                # React + Vite (JavaScript)
├── docs/                    # This plan, notes, (later) mockups
├── docker-compose.yml       # PostgreSQL only
└── README.md                # Setup and run instructions
```

**One-time setup on the Mac** (Homebrew):

- `brew install openjdk@21 node`
- Docker Desktop, for PostgreSQL. Alternatively `brew install postgresql@16` without Docker.

**Development** (live reload):

- `docker compose up -d` starts PostgreSQL on `localhost:5432`, with its data in a Docker volume.
- `cd backend && ./mvnw spring-boot:run` starts the API on `localhost:8080`.
- `cd frontend && npm run dev` starts the UI on `localhost:5173`. Vite forwards `/api` to the
  backend.

**Everyday use:** `./mvnw package` builds the frontend into the Spring Boot JAR. After that,
`java -jar backend/target/contractnotemanager.jar` serves the whole app at
`http://localhost:8080`. A `start.sh` script can run both steps.

**Backups:** a `pg_dump` script (`scripts/backup.sh`) for when the data becomes valuable.

---

## 7. Implementation Roadmap

| Phase | Deliverable | Done when |
|---|---|---|
| **0. Scaffolding** | Repo structure, Spring Boot + React (JS) skeletons, Docker Compose with Postgres, Flyway, README | The app starts on the Mac and the UI shows data from a backend endpoint |
| **1. Data model** | Flyway migrations and JPA entities from 4.6 | Migrations run cleanly; repository tests green (Testcontainers) |
| **2. Import** | Sharpfin client, paging, mapping, upsert, import log, `POST /imports` | An import (against WireMock and the real API) fills the DB; importing again doesn't duplicate |
| **3. REST API** | Order list/detail/edit/allocations/delete/revert, validation, error handling | Integration tests green; usable in Swagger UI |
| **4. UI** | Orders list, detail/edit, import dialog, import history | The full flow works in the browser: import → browse → edit → revert |
| **5. Polish** | Single-JAR packaging, `start.sh`, backup script, optional change history | Runs with one command on the Mac |

Phases 2 and 3 can be built in parallel. Phase 4 can start as soon as the API in 4.5 is fixed.

---

## 8. Open Questions

1. **Sharpfin authentication:** how does a call to `demo2.sharpfin.com/api/...` authenticate?
   An API key, a bearer token, or a browser session cookie? (Needed for phase 2.)
2. **Editable fields:** which order fields should be editable? For example price, commission,
   fees, allocations, comment, status, or everything?
3. **Write-back:** should edits ever be sent back to Sharpfin, or does the data end in the
   local DB? (Not decided yet; the design keeps it possible.)
4. **Contract notes:** given the project name and `custody.contract_notes_enabled`, should the
   app later *produce* contract notes (e.g. one PDF per portfolio allocation)?
5. **Mockup:** not decided yet. The default UI from section 5 is built first.
