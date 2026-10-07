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
- **Flow:** fetch all list pages → fetch each order's **details with allocation figures** →
  map → **upsert** by Sharpfin `key` → write an `import_run` record.
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

**Order details with allocation figures** (second call, once per order):

```
GET /api/orders/{key}?calculate_allocations=true        (key URL-encoded, e.g. LTU3MDIz…NjcwOA%3D%3D)
```

It returns the same order as the list, plus:

- a filled **`broker`** object: `key`, `name` ("BN Amro"), `bic` ("ABNANL2A"), `lei`
- the current `commission` (e.g. "1200") and `owner`. The owner can be a `system_user` or an
  `organization`, e.g. "Betty Gunnarsson"
- per allocation, the holdings figures the edit modal needs:

| Allocation field | Example (Kattegatt AB) | Modal column |
|---|---|---|
| `value` | 1761 | New Quantity / Order Quantity |
| `commission` | 1134 | Commission |
| `portfolio_quantity` | 2927.0 | Alloc pre-trade |
| `portfolio_weight` | 12.55 | % pre-trade |
| `target_quantity` | 1166.0 | Alloc post-trade (= 2927 − 1761 for a sell) |
| `target_weight` | 5.00 | % post-trade |
| `order_weight` | −7.55 | Order Weight (= post − pre weight) |

The importer calls this once per order, one after another (19 orders → 19 small calls). The
import log counts failures per order: an order whose details fail keeps its list data and
is marked "details missing".

The `version` grows with every change in Sharpfin (1 in the list sample, 7 here). That is
exactly what the change detection in 4.3 relies on.

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
| `broker` is an object (`key`, `name`, `bic`, `lei`), empty when not yet chosen; `exchange` is empty | Brokers become a reference table (like custody); `exchange` stays `JSONB` |
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

  | Field | Where in the modal | Rule |
  |---|---|---|
  | Quantity | **per portfolio**, in the allocation table's *New Quantity* column. The order quantity is their total | each ≥ 0, at most the asset's quantity decimals (funds: `qty_decimals`); total > 0 |
  | Price | *Price* field | > 0 |
  | Broker | *Counterpart* drop-down (with suggestions from brokers already used; new names can be typed) | max 200 characters |
  | Order responsible | *Owner* drop-down (known owners from imports) | must exist |
  | Commission | *Commission* field (order total) | ≥ 0. Split over the portfolios by quantity, rounded to whole units; the rounding remainder goes to the largest allocation, so the parts always add up (1,200 → 1,134 + 66) |

  Everything else (amount, status, ISIN, currency, booked, valid to, custody, source, comment)
  is read-only, both in the UI and in the API (`PATCH` rejects other fields).
- **Allocations:** the quantity is changed by editing the allocations, so the order quantity
  always equals their total and the two can never disagree. Portfolios can be added
  (*Type to start searching…* + **Add**) or removed (bin icon). The *Quantity rounding*
  buttons (None / 1 / 10 / 100) round the new quantities to that step.
- **Pre-/post-trade figures while editing:** pre-trade (holding, %) comes from the import.
  Post-trade is recalculated live from the new quantity:
  - quantity: post = pre − new quantity for a sell, pre + new quantity for a buy
  - %: post = pre % × post quantity / pre quantity. Check: 12.55 × 1166 / 2927 = 5.00 ✓,
    and 11.03 × 86 / 189 = 5.02 ✓
  - Order Weight = post % − pre %

  For a portfolio added by hand, or one without a holding, the figures show "–".
- **When edits are allowed:** while the order is `NEW`, `ON_MARKET` or `TRADED`. From
  `CONFIRMED` on, the order is locked, because it has been checked against its contract note.
- **Consequences of an edit:**
  - The order *Amount* (settlement amount) is recalculated as `price × quantity`, positive for
    sells and negative for buys, as in the Sharpfin data.
  - The order is marked `locally_modified`, so a re-import doesn't overwrite it (4.3).
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
- **Validation after extraction:**
  - **Blocking** (the note goes to the unmatched list with the reason, and the values can be
    corrected by hand): all 8 fields present, ISIN format and check digit, a 3-letter currency,
    numbers that parse.
  - **Warning only:** `quantity × price` differs from the settlement amount by more than
    ± commission + 1. In the samples the settlement amount is the gross trade value, with
    commission shown separately.
- The raw JSON answer and the model ID are stored with the note for traceability.
- **Configuration:** `ANTHROPIC_API_KEY` as an environment variable (never in git). Timeouts
  and SDK retries are on. Refusals fall back to another model via the API's server-side
  fallback option.
- **Cost:** roughly a few cents per 1–2 page PDF (input $4 / output $20 per million tokens).
- **Testability:** the extractor sits behind an interface. Tests use a fake extractor with
  fixed results. The sample PDFs below are checked against the real API in an opt-in test
  (`-Dclaude.it=true`), which runs only when an API key is set.

**What the sample notes show** (`docs/samples/contract-notes/`)

| Sample | Language | Labels used | Traps for the extraction |
|---|---|---|---|
| `abn-amro_abb.pdf` | English | Security Name, ISIN, Currency, Price, Settlement Amount, Quantity, Commission, Broker Name | Space as thousands separator (`811 809.00`) |
| `swedbank_barclays.pdf` | **Swedish** | Värdepapper, Valuta, Pris, Antal, Avräkningsbelopp, Courtage, **Mäklare** | Broker is *Mäklare* (Swedbank), **not** *Motpart* (Norion Wealth Management, which is the client side). The security name ("Barclays Bank") differs from Sharpfin's ("Barclays PLC") |
| `ubs_apple.pdf` | **German** | Wertpapier, Währung, Kurs, Anzahl, Abrechnungsbetrag, Courtage, **Geschäftsvermittler** | Broker is *Geschäftsvermittler* (UBS), **not** *Gegenpartei* (Consensus Asset Management AB) |

None of the notes states buy/sell or a trade date, so both stay optional and are not needed for
matching. The instruction to Claude therefore says:

- Labels may be in any language.
- The broker is the executing broker or bank (Broker / Mäklare / Geschäftsvermittler), never the
  counterparty/client (Motpart / Gegenpartei).
- Numbers must be returned as plain decimals, without thousands separators, with `.` as the
  decimal mark.
- The settlement amount must be returned as printed, without a sign.

**Expected results against the orders of 2026-10-07** (an acceptance test):

| Note | Order | Checks | Result |
|---|---|---|---|
| ABN Amro, ABB | Sell ABB 1,864 @ 435.52 | ISIN ✓ currency ✓ quantity ✓ price ✓, settlement 811,809.00 vs 811,809.28 (diff 0.28 ≤ 1) ✓ | **Matched** → Confirmed, green lamp |
| UBS, Apple | Sell Apple 473 @ 219.65 | all ✓, settlement 103,894.00 vs 103,894.45 (diff 0.45) ✓ | **Matched** |
| Swedbank, Barclays | Buy Barclays 98 @ 123.57 | price **123.47 ≠ 123.57**, settlement 12,100.10 vs 12,109.86 (diff **9.76**) | **Unmatched**: "price differs (123.47 vs 123.57); settlement amount differs by 9.76 GBP" |


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
| PATCH | `/api/v1/orders/{id}` (body: `price`, `commission`, `brokerId` or `brokerName`, `ownerId`, `allocations[{portfolioId, quantity}]`, `version`) | Save from the edit modal (4.4). The order quantity = total of the allocations. 409 when the order is locked or stale |
| GET | `/api/v1/portfolios?q=` | Portfolio search for *Add* in the allocation table |
| GET | `/api/v1/owners`, `/api/v1/brokers` | Options for "Owner" and "Counterpart" |
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

CREATE TABLE broker (
    id        BIGSERIAL PRIMARY KEY,
    sf_key    VARCHAR(64)  UNIQUE,                         -- NULL for brokers typed in locally
    name      VARCHAR(200) NOT NULL,                       -- "BN Amro"
    bic       VARCHAR(11),
    lei       VARCHAR(20)
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
    broker_id                 BIGINT       REFERENCES broker(id),   -- "Counterpart", editable
    exchange                  JSONB,
    sf_deleted                BOOLEAN NOT NULL DEFAULT FALSE,
    -- local bookkeeping
    locally_modified          BOOLEAN NOT NULL DEFAULT FALSE,
    sync_conflict             BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at            TIMESTAMPTZ,
    raw_payload               JSONB,                       -- last imported order, used for "revert"
    details_imported_at       TIMESTAMPTZ,                 -- when the allocation figures were fetched
    version                   INTEGER NOT NULL DEFAULT 0   -- optimistic locking for local edits
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
    original_value      NUMERIC(24,8),          -- "Order Quantity" column: value as imported
    commission          NUMERIC(24,8),          -- share of the order commission
    portfolio_quantity  NUMERIC(24,8),          -- holding before the trade ("Alloc pre-trade")
    portfolio_weight    NUMERIC(9,4),           -- % before the trade
    target_quantity     NUMERIC(24,8),          -- holding after the trade (as imported)
    target_weight       NUMERIC(9,4),           -- % after the trade (as imported)
    order_weight        NUMERIC(9,4),           -- target_weight - portfolio_weight
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
│   ├── EditOrderModal.jsx   # modal as in the screenshot: price, counterpart, owner, allocations
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
| Counterpart | `broker.name` (editable in the modal) | |
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

### 5.6 Edit modal (from the screenshot "Sell ABB")

An antd `Modal` (about 1080 px wide) opened by the pencil:

```
┌ Order ───────────────────────────────────────────────────────────────── (×) ┐
│ Sell ABB                                                    dark header      │
├──────────────────────────────────────────────────────────────────────────────┤
│ Amount       Status   ISIN           Currency   Booked       Valid to        │
│ 811,809.28   New      CH0012221716   SEK        2026-10-07   2026-10-07      │
│ Counterpart [ABN Amro ▾]  Custody [Nordnet]  Source [Rebalance]  Comment [ ] │
│ Price [435.52]  Commission [1,200]           Owner [Betty G… ▾]  Rounding    │
│                                                        [None|1|10|100]       │
│ Portfolio | New Quantity | Order Quantity | Order Weight | Alloc post/pre…   │
│ Kattegatt AB … | [1,761] | 1,761.00 | …                              [🗑]   │
│ Ohlsson Anders …| [103]  | 103.00   | …                              [🗑]   │
│ [Type to start searching…] [   ] (+ Add)                                     │
│ Total           1,864.00   1,864.00                                          │
├──────────────────────────────────────────────────────────────────────────────┤
│ (Close)                                       (Save and close)  (Save)       │
└──────────────────────────────────────────────────────────────────────────────┘
```

| Screenshot element | In this app |
|---|---|
| Header "Order / Sell ABB" | Side + asset name |
| Amount, Status, ISIN, Currency, Booked | Read-only. Amount updates live when price or quantities change |
| Valid to, Custody, Source, Comment | Shown, but **read-only** (greyed like *Custody*), since they aren't on the editable list |
| **Commission** | Editable (order total), split over the portfolios (4.4) |
| **Counterpart** | Editable (= broker). Select with search, clear button and free text |
| **Price** | Editable, number input with thousands separators |
| **Owner** | Editable (= order responsible). Select of known owners |
| **Quantity rounding** None / 1 / 10 / 100 | Rounds the *New Quantity* values to that step |
| Allocation table: Portfolio, **New Quantity** (editable), Order Quantity (as imported/last saved), bin icon | As in the screenshot |
| *Type to start searching…* + **Add** | Adds a portfolio (search over known portfolios) |
| Order Weight, Alloc post-trade, % post-trade, Alloc pre-trade, % pre-trade | From the order details call (4.2.1); post-trade and Order Weight recalculated live while editing (4.4) |
| Commission per portfolio | The allocation's `commission`, re-split when the order commission or quantities change. Read-only |
| Total row | Sum of New Quantity and Order Quantity |
| Close / **Save and close** / **Save** | Save keeps the modal open; Save and close closes it. Close with unsaved changes asks for confirmation. HTTP 409 → "order changed or locked, reload" |

---

## 6. Repository---

## 6. Repository Layout & Running on the Mac

```
ContractNoteManager/
├── backend/                 # Spring Boot (Maven wrapper ./mvnw)
├── frontend/                # React + Vite (JavaScript)
├── docs/                    # This plan, mockups, samples/contract-notes/*.pdf
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
2. **Write-back:** should edits or status changes ever be sent back to Sharpfin? (Not decided
   yet; the design keeps it possible.)
