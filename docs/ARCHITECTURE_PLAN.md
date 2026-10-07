# ContractNoteManager – Web Services Architecture Plan

## 1. Goals

1. **Import** instrument orders from the Sharpfin orders API when the user clicks a button.
2. **Persist** them in a relational database.
3. **Modify** the stored orders (edit, delete, revert to the imported values, with validation).
4. **Move orders through a status workflow**: new → on market → traded → confirmed → allocated.
5. **Match orders with contract notes**. A match confirms the order automatically.
6. **Display and edit** everything in a web UI based on the mockup.

**Context and decisions so far**

| Topic | Decision |
|---|---|
| Languages | Java backend, **plain JavaScript** frontend |
| Database | **PostgreSQL** |
| Users | **One user**, no login |
| Where it runs | **Locally on a Mac** |
| Import trigger | **Manual only**: one run per click of an "Import" button |
| Screen mockup | `docs/mockups/order-management.webp` (Order Management list screen, see section 5) |

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
| UI components | **Ant Design** (antd) | The mockup's dense table, pill pagination with page-size picker, select filters, date-range picker and drawers are all standard antd components, so the screen can be matched closely with little custom CSS |
| Data fetching | TanStack Query + a small `fetch` wrapper | Caching and automatic refresh after edits |
| Forms | Ant Design `Form` | Built-in form state and validation, consistent with the rest of the UI |
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
├── service/         # Business logic, validation, transactions, status workflow
├── matching/        # Contract note ↔ order matching
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
- Optional later: a full field-level change history per order (Hibernate Envers).

### 4.4.1 Order status workflow

```
            blue button       blue button     contract note match     blue button
   NEW ──────────────▶ ON_MARKET ───────────▶ TRADED ───────────────▶ CONFIRMED ─────────────▶ ALLOCATED
                                               ▲                         │
                                               └──── unmatch note ───────┘
```

| From | To | How | Side effects |
|---|---|---|---|
| `NEW` | `ON_MARKET` | Blue button (row or bulk) | – |
| `ON_MARKET` | `TRADED` | Blue button (row or bulk) | Sets `traded_date` = today (editable) and `settlement_date` = traded date + `asset.settlement_duration` business days |
| `TRADED` | `CONFIRMED` | **Automatic only**, when a contract note is matched to the order (4.4.2). The blue button is disabled with a tooltip "waiting for contract note" | Links the contract note |
| `CONFIRMED` | `TRADED` | Unmatching the contract note (⋯ menu) | Unlinks the note |
| `CONFIRMED` | `ALLOCATED` | Blue button (row or bulk) | Final status; the blue button is hidden |

- The rules live in one place in the backend (a small state machine in `OrderStatusService`).
  The UI only asks for "next status" and shows the button label the backend allows
  ("Send to market", "Mark traded", "Mark allocated").
- Each request contains the status the user saw, so a double click cannot skip a step
  (HTTP 409 if the order has already moved on).
- **Bulk "move status forward"** moves every ticked order one step. Orders that can't move
  (`TRADED` waiting for a note, `ALLOCATED`, deleted) are skipped. The result shows how many
  moved and which were skipped and why.
- Every change is written to `order_status_history` (from, to, when, triggered by
  `USER` / `CONTRACT_NOTE` / `IMPORT`). It is shown in the order drawer.
- **Imports and status:** Sharpfin's `status` value is mapped to these statuses on import
  (`new` → `NEW`; other values to be mapped once seen). After the status has been changed
  locally, imports don't overwrite it any more (it counts as a local edit, see 4.3).

### 4.4.2 Contract notes and matching

A contract note is the broker's or custodian's confirmation of an executed trade. Matching
one to a `TRADED` order confirms that order.

- **Input:** how contract notes arrive is still open (see open questions). The plan starts with
  **manual entry** in the UI and adds file import (CSV/Excel/PDF) once a sample exists.
- **Automatic matching**, run when a note is saved, and with a "Match again" button for
  unmatched notes. A candidate order must:
  - have status `TRADED` and not be deleted or already matched
  - have the same ISIN, side (buy/sell) and currency
  - have the same custody, when the note names one
  - have the same quantity (or amount, for amount orders)
  - have the same trade date, when the order has one
  - have a price within a small tolerance (configurable, default 0)

  Exactly one candidate → it is matched and the order becomes `CONFIRMED`. No candidate or
  several → the note stays `UNMATCHED` and the user picks the order by hand from a short list
  of the closest candidates.
- **Manual match / unmatch** from both sides: from the note (pick an order) and from the order
  (⋯ menu).
- Differences between note and order (e.g. commission, settlement amount) are shown side by
  side. Whether the note's values should overwrite the order's values is an open question.

### 4.5 REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/orders?page=&size=&sort=&dateType=booked\|traded\|settled&from=&to=&asset=&portfolio=&owner=&status=&custody=&includeDeleted=` | Paged, sortable, filterable order list (server-side, matching the mockup's toolbar) |
| GET | `/api/v1/orders/{id}` | Order detail including allocations |
| PATCH | `/api/v1/orders/{id}` | Edit order fields (requires `version`) |
| PUT | `/api/v1/orders/{id}/allocations` | Replace allocations (validated: sum = order value) |
| POST | `/api/v1/orders` | Create an order manually ("Create new" button) |
| DELETE | `/api/v1/orders/{id}` | Soft delete |
| POST | `/api/v1/orders/{id}/status/advance` (body: `expectedStatus`) | Blue button: move to the next status (4.4.1) |
| GET | `/api/v1/orders/{id}/status-history` | Status changes of an order |
| POST | `/api/v1/orders/bulk` (body: `ids`, `action` = `DELETE` \| `ADVANCE_STATUS`) | Actions on the ticked rows; returns per-order result (done / skipped + reason) |
| POST | `/api/v1/orders/{id}/revert` | Discard local edits and restore the last imported values |
| GET | `/api/v1/custodies`, `/api/v1/assets`, `/api/v1/portfolios` | Reference data for filters and drop-downs |
| GET | `/api/v1/contract-notes?status=&from=&to=&q=` | Contract note list |
| POST | `/api/v1/contract-notes` | Enter a contract note; automatic matching runs right away |
| PATCH / DELETE | `/api/v1/contract-notes/{id}` | Edit / delete a note (an unmatched one only) |
| GET | `/api/v1/contract-notes/{id}/candidates` | Orders that could match this note, best first |
| POST | `/api/v1/contract-notes/{id}/match` (body: `orderId`) | Manual match → order `CONFIRMED` |
| POST | `/api/v1/contract-notes/{id}/unmatch` | Undo a match → order back to `TRADED` |
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
orders  1──* order_status_history
orders  1──0..1 contract_note
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
    status                    VARCHAR(20)  NOT NULL,       -- NEW | ON_MARKET | TRADED | CONFIRMED | ALLOCATED
    sf_status                 VARCHAR(30),                 -- status as last imported from Sharpfin
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
    traded_date               DATE,                        -- set when the order becomes TRADED
    settlement_date           DATE,                        -- traded_date + settlement_duration business days
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
CREATE INDEX ix_orders_traded_date ON orders(traded_date);
CREATE INDEX ix_orders_settlement_date ON orders(settlement_date);
CREATE INDEX ix_orders_status      ON orders(status);
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

CREATE TABLE order_status_history (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    trigger      VARCHAR(20) NOT NULL,         -- USER | CONTRACT_NOTE | IMPORT
    note         TEXT
);

CREATE TABLE contract_note (                   -- fields refined once a real sample exists
    id                 BIGSERIAL PRIMARY KEY,
    reference          VARCHAR(100),           -- broker's note / trade reference
    custody_id         BIGINT REFERENCES custody(id),
    counterpart        VARCHAR(200),           -- broker
    isin               VARCHAR(12)   NOT NULL,
    side               VARCHAR(10)   NOT NULL, -- buy | sell
    quantity           NUMERIC(24,8),
    price              NUMERIC(24,8),
    amount             NUMERIC(24,8),          -- settlement amount
    commission         NUMERIC(24,8) NOT NULL DEFAULT 0,
    currency_code      CHAR(3)       NOT NULL,
    trade_date         DATE          NOT NULL,
    settlement_date    DATE,
    status             VARCHAR(20)   NOT NULL, -- UNMATCHED | MATCHED
    order_id           BIGINT UNIQUE REFERENCES orders(id),   -- one note ↔ one order (to be confirmed)
    matched_at         TIMESTAMPTZ,
    match_type         VARCHAR(10),            -- AUTO | MANUAL
    source_file        VARCHAR(300),           -- when imported from a file
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_contract_note_match ON contract_note(isin, side, trade_date);

CREATE TABLE import_run (
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

The mockup (`docs/mockups/order-management.webp`) shows Sharpfin's *Order Management* list
screen. The app uses the same layout and columns, with its own name and logo instead of the
Sharpfin branding.

```
frontend/src/
├── api/            # fetch wrapper + TanStack Query hooks (useOrders, useImport, …)
├── components/
│   ├── AppLayout.jsx        # dark left sidebar, page header, user name top right
│   ├── OrderToolbar.jsx     # search fields, date range, filters, buttons
│   ├── OrdersTable.jsx      # the main table
│   ├── OrderDrawer.jsx      # create / edit order + allocations + status history
│   ├── StatusButton.jsx     # blue "next status" button
│   ├── ContractNoteDrawer.jsx # enter / edit note, pick matching order
│   └── ImportDialog.jsx     # date range → run import → result counts
├── pages/
│   ├── OrdersPage.jsx       # tab "Orders"
│   ├── ContractNotesPage.jsx # tab "Contract notes"
│   └── ImportsPage.jsx      # tab "Imports" (import history)
├── App.jsx
└── main.jsx
```

### 5.1 Layout

| Mockup element | In this app |
|---|---|
| Dark left sidebar with modules (Dashboard, Wealth Management, …) | Same style, but only the module this app has: **Order Management**. The other entries are left out |
| Header "Order Management", user name/role top right, "EN" | Same. The user name is a fixed setting (one user); language is English only |
| "STAGE" badge | Shows which Sharpfin environment the data was imported from (e.g. `demo2`) |
| Tabs **Orders** / **Rebalance** | **Orders** / **Contract notes** / **Imports** (import history). There is no rebalancing in this app |

### 5.2 Toolbar → API filters

| Mockup control | Behaviour | API parameter |
|---|---|---|
| Asset search | Free text on asset name or ISIN | `asset` |
| Container search | Free text on portfolio name (orders with an allocation to that portfolio) | `portfolio` |
| Three icon toggles next to the date range | Choose which date the range filters on: **Booked** / **Traded** / **Settled** (one active at a time, default Booked) | `dateType` |
| Date range `2026-10-07 – 2026-10-07` | Range on the chosen date, default today | `from`, `to` |
| Owner / Status / Custody drop-downs | Multi-select, options filled from the stored data | `owner`, `status`, `custody` |
| Eye-slash toggle | Show deleted orders | `includeDeleted` |
| **Create new** | Opens the order drawer empty | `POST /orders` |
| *(new)* **Import from Sharpfin** | Opens the import dialog. Placed next to "Create new" | `POST /imports` |

### 5.3 Table columns → data

| Column | Source | Sortable |
|---|---|---|
| ☐ (row selection) | Bulk actions on ticked rows | – |
| Asset | `asset.name` | ✓ |
| ISIN | `asset.isin` | ✓ |
| Buy / Sell | `side` | |
| Status | `status` as a coloured tag: New, On market, Traded, Confirmed, Allocated (+ "edited" / "conflict" badges) | ✓ |
| Booked | `booked_date` (default sort) | ✓ |
| Valid to | `valid_to` | ✓ |
| Quantity | `value` when `order_type = quantity`; **empty** for amount orders (as with AMF Räntefond Lång in the mockup) | |
| Price | `price` | |
| Amount | `settlement_amount` (negative for buys) | |
| Commission | `commission` | |
| Curr | `currency_code` | |
| Owner | `owner.name` | ✓ |
| Counterpart | `broker` (empty in the current data) | |
| Custody | `custody.name` | |
| ⚙ (header) | Show/hide columns; the choice is remembered in the browser | – |

Numbers are right-aligned with thousands separators and 2 decimals (`811,809.28`), as in the
mockup. The table is server-side paged with page size 10 and a size picker, as in the mockup.

### 5.4 Row actions

| Icon | Action |
|---|---|
| Blue (phone) | **Move status forward** (4.4.1). Tooltip names the step ("Send to market", "Mark traded", "Mark allocated"); disabled for `TRADED` ("waiting for contract note"); hidden for `ALLOCATED` |
| Green (Excel) | Left out (not needed for this app) |
| Pencil | Opens the **order drawer**: order fields plus an allocations table (portfolio, value) with a running total that must match the order value. Save / Cancel |
| ⋯ (more) | Show/unmatch contract note, Revert to imported values, Delete / Restore, Show conflict details |

**Bulk actions** for ticked rows, shown above the table when rows are ticked: **Move status
forward** and **Delete**, both asking for confirmation and then showing the result (moved /
skipped with reason).

### 5.5 Contract notes tab

Same look as the orders table. The columns are trade date, reference, ISIN/asset, side,
quantity, price, amount, commission, currency, custody, counterpart, status
(Unmatched / Matched) and the matched order. Actions:

- **New contract note**: a form. Saving runs the automatic match at once and shows the result:
  "matched with order …" or "no unique match".
- **Match** on an unmatched note: a drawer listing the candidate orders, best match first,
  with differing fields highlighted. Picking one confirms the order.
- **Unmatch** on a matched note: the order goes back to `TRADED`.

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
| **1. Data model** | Flyway migrations and JPA entities from 4.6 (incl. status history and contract notes) | Migrations run cleanly; repository tests green (Testcontainers) |
| **2. Import** | Sharpfin client, paging, mapping, upsert, import log, `POST /imports` | An import (against WireMock and the real API) fills the DB; importing again doesn't duplicate |
| **3. REST API** | Order list/detail/edit/allocations/delete/revert, status workflow, bulk actions, validation, error handling | Integration tests green, incl. every allowed and forbidden status step; usable in Swagger UI |
| **3b. Contract notes** | Contract note CRUD, automatic + manual matching, unmatch | Tests cover unique match, no match, several candidates, unmatch |
| **4. UI** | Orders tab as in the mockup, order drawer, status button and bulk actions, contract notes tab, import dialog, import history | The full flow works in the browser: import → send to market → traded → enter contract note → auto-confirmed → allocated |
| **5. Polish** | Single-JAR packaging, `start.sh`, backup script, optional change history | Runs with one command on the Mac |

Phases 2 and 3 can be built in parallel. Phase 4 can start as soon as the API in 4.5 is fixed.

---

## 8. Open Questions

1. **Sharpfin authentication:** how does a call to `demo2.sharpfin.com/api/...` authenticate?
   An API key, a bearer token, or a browser session cookie? (Needed for phase 2.)
2. **Editable fields:** which order fields should be editable? For example price, commission,
   fees, allocations, comment, or everything?
3. **Write-back:** should edits or status changes ever be sent back to Sharpfin? (Not decided
   yet; the design keeps it possible.)
4. **Where do contract notes come from?** Entered by hand, files from the broker/custodian
   (PDF, CSV, Excel, e-mail), or an API? A sample would define the fields and the file import.
5. **Matching rules:** are the criteria in 4.4.2 right (ISIN, side, currency, custody,
   quantity/amount, trade date, price tolerance)? Should a price difference be allowed?
6. **One-to-one?** Can one contract note cover several orders, or one order be confirmed by
   several notes (e.g. partial fills)? The plan assumes one note ↔ one order.
7. **After a match:** should the note's price, commission and settlement amount overwrite the
   order's values, or only be shown next to them?
8. **Moving a status back:** apart from unmatching a note, should it be possible to step a status
   back by mistake (e.g. On market → New)?
9. **Other Sharpfin statuses:** besides `new`, which status values can the API return, and how
   do they map to the five statuses?
