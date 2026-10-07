# ContractNoteManager

A local web app that

1. **imports instrument orders from Sharpfin** when you click *Import from Sharpfin*,
2. stores them in **PostgreSQL**,
3. moves them through the status workflow **New → On market → Traded → Confirmed → Allocated**
   (blue button, one order or several at once),
4. **reads contract-note PDFs with Claude** (dropped on the ticked orders) and **matches** them:
   a match confirms the order and turns its *Contract Note Match* lamp green; everything else lands
   in *Unmatched Contract Notes* with the reason,
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
| Import the day's orders | *Import from Sharpfin* → pick the date range → *Import* |
| Send to market / mark traded / mark allocated | blue button in the row, or tick rows → *Move status forward* |
| Edit price, commission, counterpart, owner, quantities | pencil → order window → *Save* / *Save and close* (until the order is Confirmed) |
| Confirm with contract notes | tick the Traded orders → drop the PDFs in the area above the table |
| Check a match | click the green lamp → PDF next to the order's values |
| Fix a note that did not match | *Unmatched Contract Notes* → open it → correct values → *Save and match again* |
| Undo a mistake | ⋯ → *Delete*; the next import brings the order back as a new order |

**Matching rules:** same ISIN, currency and quantity (amount orders: amount), exactly the same price,
settlement amount within ±1 of the currency (signs ignored), the order must be *Traded*, and exactly one
ticked order may fit. Name and broker are not compared. The note never overwrites order values.

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

## 6. Backups

```bash
docker compose exec db pg_dump -U cnm contractnotemanager > backup-$(date +%F).sql
```

## Left out on purpose

- *Create new* (manual orders) – orders always come from Sharpfin.
- The Excel button, *Fund Accounting Orders* and the *show deleted* toggle from the Sharpfin screen.
- Login/roles (single local user) and writing anything back to Sharpfin.
