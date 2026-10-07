# ContractNoteManager

A local web app that

1. **imports instrument orders from Sharpfin** when you click *Import from Sharpfin*,
2. stores them in **PostgreSQL**,
3. moves them through the status workflow **New → On market → Traded → Confirmed → Allocated**
   (blue button, one order or several at once),
4. **reads contract-note PDFs with Claude** (dropped on the ticked orders) and **matches** them:
   *Matched* confirms the order (green lamp), *Partially matched* links the note to the order (orange lamp)
   and can update the order with the note's values, *No match* explains why; all notes are listed in the
   *Contract Notes* tab and are re-evaluated when orders change,
5. lets you **edit** price, commission, counterpart (broker), owner and the per-portfolio quantities
   in the order window.

Nothing is ever written back to Sharpfin. The design is in [docs/ARCHITECTURE_PLAN.md](docs/ARCHITECTURE_PLAN.md).

Stack: Java 21 / Spring Boot 3.5, PostgreSQL 16 (Flyway), React 19 + Vite + Ant Design (plain JavaScript),
Claude API via the official Java SDK (`claude-opus-5-5`).

---

## 1. One-time setup on the Mac

```bash
brew install openjdk@21 node
```

Node.js must be **20.19+ or 22.12+** (`node -v`); older versions break the UI build.

PostgreSQL – either **Docker Desktop** (recommended; `start.sh` starts the app's own database on port **5433**,
so it doesn't clash with any other PostgreSQL on your Mac) or Homebrew (port 5432, used when Docker isn't available):

```bash
brew install postgresql@16 && brew services start postgresql@16
psql postgres -c "CREATE USER cnm WITH PASSWORD 'cnm';"
psql postgres -c "CREATE DATABASE contractnotemanager OWNER cnm;"
```

### Settings

```bash
cp backend/application-local.yml.example backend/application-local.yml
```

and fill in the Sharpfin username/password and your Anthropic API key. That file is git-ignored.
Environment variables work too: `SHARPFIN_USERNAME`, `SHARPFIN_PASSWORD`, `ANTHROPIC_API_KEY`,
`SHARPFIN_BASE_URL` (default `https://demo2.sharpfin.com`), `DB_URL` (default `jdbc:postgresql://localhost:5433/contractnotemanager`).

## 2. Start

```bash
./start.sh            # first run builds everything; later runs start immediately
./start.sh --build    # rebuild after pulling new code
```

If you use Docker, `start.sh` starts Docker Desktop when it isn't running yet (wait for *Engine running*
the first time). With a Homebrew PostgreSQL already running, Docker is not needed.

Open **http://localhost:8080**. The app only listens on this Mac (127.0.0.1).
The tables are created automatically on the first start.

## 3. How to use it

| Step | Where |
|---|---|
| Import the day's orders | *Import from Sharpfin* → choose *Booked / Traded / Settled* and the date range → *Import* |
| Send to market / mark traded / mark allocated | blue button in the row, or tick rows → *Move status forward* |
| Edit price, commission, counterpart, owner, quantities | pencil → order window → *Save* / *Save and close* (until the order is Confirmed) |
| Confirm with contract notes | tick the Traded orders → drop the PDFs in the area above the table |
| Check a match | click the green or orange lamp → PDF, the six checks and the values compared |
| Partially matched note | open it → tick the differing properties under *Overwrite on order* (quantity, price, commission; optionally the counterpart) → *Overwrite selected on order* → the note is checked again and becomes Matched when all six checks pass |
| Note that did not match | *Contract Notes* → open it → fix the order or correct a misread value → *Re-evaluate* |
| Notes uploaded before the order was ready | re-evaluated automatically after order edits, status changes and imports; or *Re-evaluate all* |
| Undo a mistake | ⋯ → *Delete*; the next import brings the order back as a new order |

**Matching rules:** ISIN and Buy/Sell must agree exactly (a note without Buy/Sell is *No match*); the order
must be *Traded*. Then six checks:

| # | Check | Rule |
|---|---|---|
| 1 | Currency | equal |
| 2 | Quantity | equal (amount orders: quantity × price vs. the order amount, ±1) |
| 3 | Price | exactly equal |
| 4 | Commission | note = order |
| 5 | Settlement amount | ±1, with the note's commission taken out first (Sharpfin's amount excludes commission) |
| 6 | Note adds up | price × quantity + commission (buy) / − commission (sell) = settlement amount, ±1 |

6 of 6 → **Matched** (order confirmed) · 4–5 → **Partially matched** · 3 or fewer → **No match**.
If two orders fit equally well, nothing is linked – tick only the right one and upload again.

**Re-imports:** unchanged orders are skipped; changed orders are updated unless you edited them or they are
already confirmed – then your values stay and the order is marked *conflict* (⋯ → *Revert* takes Sharpfin's values).
Statuses only move forward (`finalized` in Sharpfin = *Allocated*).

## 4. Development

```bash
docker compose up -d                       # database
cd backend && ./mvnw spring-boot:run       # API on :8080 (Swagger UI: /swagger-ui.html)
cd frontend && npm install && npm run dev  # UI with live reload on http://localhost:5173
```

Tests:

```bash
cd backend && ./mvnw test     # unit + API tests on an embedded PostgreSQL with a fake Sharpfin (no Docker needed)
cd frontend && npm test       # the order-window calculations
# optional, sends the three sample PDFs to Claude (a few cents):
cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Dtest=ClaudeExtractionLiveTest -Dclaude.it=true
```

The sample contract notes are in `docs/samples/contract-notes/`; the expected results
(ABB and Apple match, Barclays does not: price 123.47 vs 123.57) are part of the tests.

## 5. Sharpfin login

The app logs in with `POST /api/sessions` and a JSON body `{"email": …, "password": …}`, keeps the
session cookie for the import, logs in again once on HTTP 401 and calls `DELETE /api/sessions` at the end.
If the first real import fails with *"Sharpfin login failed (HTTP …)"*, adjust `sharpfin.login.*` in
`application-local.yml` (method, field names, or `token-field` if a token comes back instead of a cookie).

**Debugging:** every call to Sharpfin (method, URL, HTTP status – never the password) is written to the
terminal and to `backend/logs/contractnotemanager.log`. The import result and the *Imports* tab also show the
exact orders URL that was called. The values sent as `date_type` for Booked/Traded/Settled can be changed under
`sharpfin.date-types` in `application-local.yml` if Sharpfin uses other names.

## 6. Start over / backups

**Clear everything** (orders, contract notes, import history) and start with an empty database:

```bash
./reset-db.sh          # shows what is in the database, asks you to type YES, writes a backup first
```

The tables stay, so the app can keep running – just reload the page. The backup lands in `backups/`
(git-ignored, it contains client data).

**Backup by hand:**

```bash
docker compose exec -T db pg_dump -U cnm contractnotemanager > backups/backup-$(date +%F).sql
```

**Restore a backup** (replaces everything currently in the database; stop the app first with Ctrl+C):

```bash
docker compose exec -T db psql -U cnm -d contractnotemanager -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker compose exec -T db psql -U cnm -d contractnotemanager < backups/before-reset-YYYYMMDD-HHMMSS.sql
./start.sh
```

## Left out on purpose

- *Create new* (manual orders) – orders always come from Sharpfin.
- The Excel button, *Fund Accounting Orders* and the *show deleted* toggle from the Sharpfin screen.
- Login/roles (single local user) and writing anything back to Sharpfin.
