# ContractNoteManager – Web Services Architecture Plan

## 1. Goals

1. **Import** instrument orders from the Sharpfin orders API when the user clicks a button.
2. **Persist** them in a relational database.
3. **Modify** the stored orders (edit, delete, revert to the imported values, with validation).
4. **Move orders through a status workflow**: new → on market → traded → confirmed → allocated.
5. **Upload contract notes (PDF)**, let an LLM (Claude) read the key fields, and **match** them
   with orders. A match confirms the order automatically.
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
| Contract note reading | **Claude API** (official Java SDK `com.anthropic:anthropic-java`, model `claude-opus-5-5`) | Contract-note PDFs look different for every broker. Claude reads the PDF directly and returns the fields as typed JSON (structured output), so no per-broker templates are needed |
| PDF preview | Browser's built-in PDF viewer (`<iframe>`) | No extra library |
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
├── contractnote/    # PDF upload, Claude extraction, contract note ↔ order matching
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

**Authentication: session based** (as in Sharpfin's own Python example, which calls
`/api/sessions` with `Content-type: application/json`):

1. At the start of each import, `SharpfinSessionClient` opens a session via `/api/sessions` with
   `SHARPFIN_USERNAME` / `SHARPFIN_PASSWORD`, sent as a JSON body.
2. It keeps the session cookie or token from the response, using a cookie store in the HTTP client.
3. It sends that session on every `/api/orders/paginated` request.
4. On HTTP 401 it logs in once more and retries; a second 401 fails the import with the message
   "Sharpfin login failed".
5. It ends the session after the import (if the API supports `DELETE /api/sessions`).

Always over **HTTPS**. The example uses `HTTPConnection`, which is plain HTTP, but credentials
must not travel unencrypted. The exact request (HTTP method, JSON field names, whether a cookie
or a token comes back) is checked once against demo2 from the Mac in phase 2. It is one small
class, so differences are cheap to adjust.

### 4.3 Local edits vs. re-imports

Because imports are manual, the rule can stay simple: **local edits win**.

- Editing an order sets `locally_modified = true`.
- A later import does **not** overwrite a locally modified order. If Sharpfin's `version` has
  gone up since, the order is flagged with `sync_conflict = true` and shown with a badge, so
  the change can be checked.
- **"Revert"** on an order discards the local edits and restores the last imported values.
- **Deleting** an order removes it from the local database. The next import of that day brings it
  back as a fresh order, which is how you "start over" (statuses never go backwards, see 4.4.1).

### 4.4 Modifying data

- **Editable fields (only these):**

  | Field | Column | Input | Rule |
  |---|---|---|---|
  | Quantity | `value` | number | > 0, at most the asset's quantity decimals (funds: `qty_decimals`) |
  | Price | `price` | number | > 0 |
  | Broker | `counterpart` | text, with suggestions from brokers already used | max 200 characters |
  | Order responsible | `owner_id` | drop-down of known owners (from imports) | must exist |

  Everything else is read-only in the UI and in the API (`PATCH` rejects other fields).
- **When edits are allowed:** while the order is `NEW`, `ON_MARKET` or `TRADED`. From
  `CONFIRMED` on, the order is locked, because it has been checked against its contract note.
- **Consequences of an edit:**
  - The settlement amount is recalculated as `price × quantity`, positive for sells and
    negative for buys, as in the Sharpfin data.
  - The order is marked `locally_modified`, so a re-import doesn't overwrite it (4.3).
  - **Allocations:** they must add up to the quantity, so changing the quantity shows the
    allocations in the edit window with the difference highlighted. How they should be adjusted
    is an open question (see section 8).
- An `@Version` column prevents saving stale data, e.g. when the same order is edited in two
  browser tabs (HTTP 409 and a "reload" message).
- **Delete** removes the order, its allocations and status history. A linked contract note is
  kept and goes back to the *Unmatched Contract Notes* list. Re-importing brings the order back
  as a new order ("start over").
- Optional later: a full field-level change history per order (Hibernate Envers).

### 4.4.1 Order status workflow

```
         blue button         blue button       contract note match      blue button
  NEW ─────────────▶ ON_MARKET ─────────────▶ TRADED ───────────────▶ CONFIRMED ─────────────▶ ALLOCATED
```

Statuses only ever move **forward**. To undo a mistake, delete the order and start over (4.4).

| From | To | How | Side effects |
|---|---|---|---|
| `NEW` | `ON_MARKET` | Blue button (row or bulk) | – |
| `ON_MARKET` | `TRADED` | Blue button (row or bulk) | Sets `traded_date` = today (editable) and `settlement_date` = traded date + `asset.settlement_duration` business days |
| `TRADED` | `CONFIRMED` | **Automatic only**, when a contract note is matched to the order (4.4.2). The blue button is disabled with a tooltip "waiting for contract note" | Links the contract note |
| `CONFIRMED` | `ALLOCATED` | Blue button (row or bulk) | Final status; the blue button is hidden |

- The rules live in one place in the backend (a small state machine in `OrderStatusService`).
  The UI only asks for "next status" and shows the button label the backend allows
  ("Send to market", "Mark traded", "Mark allocated").
- Each request contains the status the user saw, so a double click cannot skip a step
  (HTTP 409 if the order has already moved on).
- **Bulk "move status forward"** moves every ticked order one step. Orders that can't move
  (`TRADED` waiting for a note, `ALLOCATED`) are skipped. The result shows how many moved and
  which were skipped and why.
- Every change is written to `order_status_history` (from, to, when, triggered by
  `USER` / `CONTRACT_NOTE` / `IMPORT`). It is shown in the order drawer.
- **Sharpfin statuses on import:**

  | Sharpfin `status` | Local status |
  |---|---|
  | `new` | `NEW` |
  | `on_market` | `ON_MARKET` |
  | `traded` | `TRADED` |
  | `finalized` | `ALLOCATED` |

  An import can move a status forward but never back. A Sharpfin `traded` order still needs a
  contract note to become `CONFIRMED`.

### 4.4.2 Contract notes: upload, reading with Claude, matching

A contract note is the broker's confirmation of an executed trade, delivered as a **PDF** whose
layout differs per broker. Every note contains: **name** (instrument), **ISIN**, **currency**,
**quantity**, **price**, **settlement amount**, **broker** and **commission**.

**Flow**

```
tick orders ─▶ drop PDFs in the bulk-action area ─▶ POST /contract-notes (multipart)
   ─▶ store PDF + SHA-256 (duplicate files are rejected)
   ─▶ Claude reads the PDF ─▶ typed fields (ContractNoteExtraction)
   ─▶ validate fields ─▶ match against the ticked orders
        ├─ exactly one match ─▶ note linked to the order, order → CONFIRMED, green lamp
        └─ otherwise          ─▶ note goes to "Unmatched Contract Notes" with the reason
```

Several PDFs can be dropped at once. Each is processed on its own, and the result is shown
per file.

**Reading the PDF with Claude** (`ContractNoteExtractor`)

- One Messages API call per PDF, using the official Java SDK (`com.anthropic:anthropic-java`):
  - model `claude-opus-5-5`
  - the PDF sent as a base64 `document` block
  - a short instruction that also says how sign and decimal conventions vary between brokers
  - **structured output** bound to a Java record, so the answer is always valid JSON in the
    expected shape:

  ```java
  record ContractNoteExtraction(
      String instrumentName, String isin, String currency,
      String quantity, String price, String settlementAmount,
      String broker, String commission,
      String side,       // "buy" | "sell" | null if not stated
      String tradeDate,  // ISO date or null if not stated
      List<String> warnings) {}   // e.g. "two trades on one note", "illegible amount"
  ```

  Numbers are returned as strings and parsed to `BigDecimal` in Java, so no rounding happens
  on the way.
- **Validation after extraction:** ISIN format and check digit, a 3-letter currency, numbers
  that parse, and `quantity × price` ≈ settlement amount ± commission. If any check fails, the
  note goes to the unmatched list with the reason, and the values can be corrected by hand.
- The raw JSON answer and the model ID are stored with the note for traceability.
- **Configuration:** `ANTHROPIC_API_KEY` as an environment variable (never in git). Timeouts
  and SDK retries are on. Refusals fall back to another model via the API's server-side
  fallback option.
- **Cost:** roughly a few cents per 1–2 page PDF (input $4 / output $20 per million tokens).
- **Testability:** the extractor sits behind an interface. Tests use a fake extractor with
  fixed results. A small set of real, anonymised PDFs checks the extraction quality by hand.

**Matching rules**

Candidates are the **ticked orders** that have status `TRADED` and no linked note. All of these
must hold:

| Field | Rule |
|---|---|
| ISIN | equal |
| Currency | equal |
| Quantity | equal (for amount-type orders: the order `value` is compared with the settlement amount instead) |
| Price | **exactly** equal (after normalising decimals, e.g. `435.5` = `435.520`) |
| Settlement amount | equal within **± 1 unit** of the currency (1 SEK, 1 EUR, …), comparing absolute values because brokers use different sign conventions |
| Side | equal, when the note states it |

The name and the broker are not used for matching. **Nothing on the order is overwritten by the
note.** The note's values (including broker, commission and settlement amount) are stored with
the note and shown next to the order's values.

- **Exactly one** candidate → match: the note is linked to the order (one note per order,
  enforced by a unique key), the order becomes `CONFIRMED`, and the **Contract Note Match**
  lamp turns green.
- **None or several** → the note goes to **Unmatched Contract Notes** with the reason, e.g. "no
  ticked order with ISIN CH0012221716", "price 435.50 ≠ 435.52", "settlement amount differs
  by 3.20 SEK", "order not yet TRADED".
- From the unmatched list a note can be matched again. This time the candidates are all
  `TRADED` orders without a note, and the same rules apply. Values can be corrected first, and
  notes can be deleted.

### 4.5 REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/orders?page=&size=&sort=&dateType=booked\|traded\|settled&from=&to=&asset=&portfolio=&owner=&status=&custody=&noteMatch=` | Paged, sortable, filterable order list (server-side, matching the mockup's toolbar) |
| GET | `/api/v1/orders/{id}` | Order detail including allocations |
| PATCH | `/api/v1/orders/{id}` (body: `quantity`, `price`, `broker`, `ownerId`, `version`) | Edit the four editable fields (4.4); 409 when the order is locked or stale |
| GET | `/api/v1/owners` | Options for "Order responsible" |
| POST | `/api/v1/orders` | Create an order manually ("Create new" button) |
| DELETE | `/api/v1/orders/{id}` | Delete (a linked note becomes unmatched) |
| POST | `/api/v1/orders/{id}/status/advance` (body: `expectedStatus`) | Blue button: move to the next status (4.4.1) |
| GET | `/api/v1/orders/{id}/status-history` | Status changes of an order |
| POST | `/api/v1/orders/bulk` (body: `ids`, `action` = `DELETE` \| `ADVANCE_STATUS`) | Actions on the ticked rows; returns per-order result (done / skipped + reason) |
| POST | `/api/v1/orders/{id}/revert` | Discard local edits and restore the last imported values |
| GET | `/api/v1/custodies`, `/api/v1/assets`, `/api/v1/portfolios` | Reference data for filters and drop-downs |
| POST | `/api/v1/contract-notes` (multipart: `files[]`, `orderIds[]`) | Upload PDFs from the bulk-action area → extract → match against the given orders; result per file |
| GET | `/api/v1/contract-notes?status=UNMATCHED` | Unmatched Contract Notes list |
| GET | `/api/v1/contract-notes/{id}` | Note with extracted fields, reason and match result |
| GET | `/api/v1/contract-notes/{id}/file` | The original PDF (for preview) |
| PATCH | `/api/v1/contract-notes/{id}` | Correct extracted values (unmatched notes only) |
| POST | `/api/v1/contract-notes/{id}/rematch` | Try matching again against all `TRADED` orders without a note |
| DELETE | `/api/v1/contract-notes/{id}` | Delete an unmatched note |
| GET | `/api/v1/orders/{id}/contract-note` | The note linked to an order |
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
    counterpart               VARCHAR(200)                 -- broker, editable in the edit modal
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

CREATE TABLE contract_note (
    id                 BIGSERIAL PRIMARY KEY,
    file_name          VARCHAR(300)  NOT NULL,
    file_sha256        CHAR(64)      NOT NULL UNIQUE,   -- rejects uploading the same PDF twice
    file_content       BYTEA         NOT NULL,          -- the PDF itself (small files, single user)
    -- fields read by Claude (editable while unmatched)
    instrument_name    VARCHAR(300),
    isin               VARCHAR(12),
    currency_code      CHAR(3),
    quantity           NUMERIC(24,8),
    price              NUMERIC(24,8),
    settlement_amount  NUMERIC(24,8),
    broker             VARCHAR(200),
    commission         NUMERIC(24,8),
    side               VARCHAR(10),                     -- if stated on the note
    trade_date         DATE,                            -- if stated on the note
    extraction_json    JSONB,                           -- raw answer from Claude
    extraction_model   VARCHAR(50),                     -- e.g. claude-opus-5-5
    -- matching
    status             VARCHAR(20)   NOT NULL,          -- MATCHED | UNMATCHED | EXTRACTION_FAILED
    unmatched_reason   TEXT,
    order_id           BIGINT UNIQUE REFERENCES orders(id) ON DELETE SET NULL,  -- one note ↔ one order
    matched_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_contract_note_status ON contract_note(status);

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
- The Sharpfin credentials live in the environment variables `SHARPFIN_USERNAME` /
  `SHARPFIN_PASSWORD` (or a git-ignored `application-local.yml`), never in git. They are never
  logged, and the connection to Sharpfin is always HTTPS.
- The Claude API key (`ANTHROPIC_API_KEY`) is also an environment variable.
- **Contract-note PDFs are sent to the Claude API** (Anthropic) to be read. This has been
  approved. Only the PDF is sent, never other order or client data.
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
│   ├── EditOrderModal.jsx   # modal: quantity, price, broker, order responsible
│   ├── OrderDetailsDrawer.jsx # read-only details, allocations, status history, contract note
│   ├── StatusButton.jsx     # blue "next status" button
│   ├── BulkActionBar.jsx    # appears when rows are ticked: status forward, delete, PDF drop area
│   ├── ContractNoteDropzone.jsx # antd Upload.Dragger, multiple PDFs, per-file result
│   ├── NoteMatchLamp.jsx    # green / grey lamp + popover with the note
│   ├── ContractNoteDrawer.jsx # PDF preview next to extracted fields, correct + rematch
│   └── ImportDialog.jsx     # date range → run import → result counts
├── pages/
│   ├── OrdersPage.jsx       # tab "Orders"
│   ├── UnmatchedNotesPage.jsx # tab "Unmatched Contract Notes"
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
| Tabs **Orders** / **Rebalance** | **Orders** / **Unmatched Contract Notes** (with a count badge) / **Imports** (import history). There is no rebalancing in this app |

### 5.2 Toolbar → API filters

| Mockup control | Behaviour | API parameter |
|---|---|---|
| Asset search | Free text on asset name or ISIN | `asset` |
| Container search | Free text on portfolio name (orders with an allocation to that portfolio) | `portfolio` |
| Three icon toggles next to the date range | Choose which date the range filters on: **Booked** / **Traded** / **Settled** (one active at a time, default Booked) | `dateType` |
| Date range `2026-10-07 – 2026-10-07` | Range on the chosen date, default today | `from`, `to` |
| Owner / Status / Custody drop-downs | Multi-select, options filled from the stored data | `owner`, `status`, `custody` |
| Eye-slash toggle | Left out, because deleted orders are removed (4.4) | – |
| *(new)* Contract Note Match filter | All / Matched / Not matched | `noteMatch` |
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
| Counterpart | The order's broker (editable) | |
| Custody | `custody.name` | |
| **Contract Note Match** *(new)* | 🟢 green lamp when a note is matched, grey otherwise. Clicking the lamp opens the note (PDF + extracted values) next to the order | ✓ |
| ⚙ (header) | Show/hide columns; the choice is remembered in the browser | – |

Numbers are right-aligned with thousands separators and 2 decimals (`811,809.28`), as in the
mockup. The table is server-side paged with page size 10 and a size picker, as in the mockup.

### 5.4 Row actions

| Icon | Action |
|---|---|
| Blue (phone) | **Move status forward** (4.4.1). Tooltip names the step ("Send to market", "Mark traded", "Mark allocated"); disabled for `TRADED` ("waiting for contract note"); hidden for `ALLOCATED` |
| Green (Excel) | Left out (not needed for this app) |
| Pencil | Opens the **edit modal** (5.6). Disabled from `CONFIRMED` on (tooltip "locked after contract note match") |
| ⋯ (more) | Details (allocations, status history), Show contract note, Revert to imported values, Delete, Show conflict details |

**Bulk-action area** (appears above the table when rows are ticked):

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│ 3 orders selected   [ Move status forward ]  [ Delete ]                           │
│ ┌───────────────────────────────────────────────────────────────────────────────┐ │
│ │   ⇪  Drop contract note PDFs here or click to choose files                    │ │
│ │      They are read by Claude and matched against the 3 selected orders        │ │
│ └───────────────────────────────────────────────────────────────────────────────┘ │
│ ✔ nordnet_abb.pdf      → matched with ABB (sell 1,864 @ 435.52), order confirmed  │
│ ✖ nordnet_tesla.pdf    → not matched: price 750.10 ≠ 750.17  [open]               │
└───────────────────────────────────────────────────────────────────────────────────┘
```

- **Move status forward** and **Delete** ask for confirmation, then show the result
  (moved / skipped with reason).
- The **drop area** (antd `Upload.Dragger`) takes several PDFs at once. A spinner shows per file
  while Claude reads it, which takes a few seconds per note. Afterwards each file shows its
  result, and the table refreshes, so matched orders show the green lamp and status Confirmed.

### 5.5 Unmatched Contract Notes

A tab with a count badge, styled like the orders table. The columns are file name, upload time,
name, ISIN, currency, quantity, price, settlement amount, broker, commission and **reason
not matched**.

- Clicking a row opens a drawer with the **PDF preview on the left** and the **extracted fields on
  the right**, plus the closest orders and the fields that differ.
- **Correct** fields (e.g. when Claude misread a value) → **Match again**. This tries all
  `TRADED` orders without a note.
- **Delete** the note, e.g. when the wrong file was uploaded.

### 5.6 Edit modal

An antd `Modal` opened by the pencil, laid out to match the screenshot you are sending:

- **Header:** asset name, ISIN, side, current status.
- **Fields:** Quantity, Price, Broker, Order responsible, with live validation. The other values
  are shown read-only for context.
- **Recalculated settlement amount**, shown as soon as quantity or price changes.
- **Allocations**, shown when the quantity changes, with the difference to the new quantity.
- **Save** / **Cancel** buttons. If someone else changed the order meanwhile (HTTP 409), a
  "reload" message appears.

The layout will be adjusted to the modal screenshot once it arrives.

---

## 6.---

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
| **3b. Contract notes** | PDF upload, Claude extraction (Java SDK, structured output), validation, matching rules, unmatched list API | Tests cover unique match, no match, several candidates, price exact, settlement ± 1, duplicate file; the extraction is checked on real, anonymised PDFs |
| **4. UI** | Orders tab as in the mockup, order drawer, status button and bulk actions, contract notes tab, import dialog, import history | The full flow works in the browser: import → send to market → traded → tick orders → drop PDFs → auto-confirmed with green lamp → allocated; unmatched notes corrected and re-matched |
| **5. Polish** | Single-JAR packaging, `start.sh`, backup script, optional change history | Runs with one command on the Mac |

Phases 2 and 3 can be built in parallel. Phase 4 can start as soon as the API in 4.5 is fixed.

---

## 8. Open Questions

1. **Sharpfin login details:** the endpoint is `/api/sessions` (4.2.1). Two things are still to be
   confirmed, either from the Python script or on the first test from the Mac: how the
   username/password are sent (JSON field names, `POST`?), and whether a cookie or a token
   comes back.
2. **Allocations when the quantity changes:** should the app
   (a) scale the allocations proportionally (rounded, with the remainder on the largest one),
   (b) require editing the allocations in the modal too, or
   (c) only warn and let the allocations differ?
3. **Edit modal screenshot:** not received yet. Section 5.6 is a placeholder until then.
4. **Sample PDFs:** none received yet. Two or three real (anonymised) contract notes from
   different brokers are needed to test the extraction.
5. **Write-back:** should edits or status changes ever be sent back to Sharpfin? (Not decided
   yet; the design keeps it possible.)
