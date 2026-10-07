#!/usr/bin/env bash
# Empties the ContractNoteManager database (orders, contract notes, import history, reference data).
# The tables themselves stay, so the app keeps working; a backup is written to backups/ first.
#   ./reset-db.sh          asks for confirmation
#   ./reset-db.sh --yes    no question (e.g. for scripts)
set -euo pipefail
cd "$(dirname "$0")"

TABLES="contract_note, order_status_history, order_allocation, orders, portfolio, broker, owner, asset, custody, import_run"
SQL="TRUNCATE $TABLES RESTART IDENTITY CASCADE;"

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Where is the database? The app's Docker database (port 5433) first, else a local PostgreSQL on 5432.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
   && [[ -n "$(docker compose ps --status running -q db 2>/dev/null)" ]]; then
  MODE="Docker database (port 5433)"
  psql_run()  { docker compose exec -T db psql -U cnm -d contractnotemanager -v ON_ERROR_STOP=1 "$@"; }
  dump_run()  { docker compose exec -T db pg_dump -U cnm contractnotemanager; }
elif port_open 5432; then
  PSQL="$(command -v psql || true)"
  [[ -z "$PSQL" && -x /opt/homebrew/opt/postgresql@16/bin/psql ]] && PSQL=/opt/homebrew/opt/postgresql@16/bin/psql
  [[ -z "$PSQL" && -x /usr/local/opt/postgresql@16/bin/psql ]] && PSQL=/usr/local/opt/postgresql@16/bin/psql
  if [[ -z "$PSQL" ]]; then
    echo "psql not found – install it with: brew install postgresql@16"; exit 1
  fi
  MODE="local PostgreSQL (port 5432)"
  export PGPASSWORD="${DB_PASSWORD:-cnm}"
  psql_run()  { "$PSQL" -h localhost -p 5432 -U "${DB_USERNAME:-cnm}" -d contractnotemanager -v ON_ERROR_STOP=1 "$@"; }
  dump_run()  { "$(dirname "$PSQL")/pg_dump" -h localhost -p 5432 -U "${DB_USERNAME:-cnm}" contractnotemanager; }
else
  echo "No database found. Start it first (./start.sh, or Docker Desktop / your PostgreSQL)."
  exit 1
fi

COUNT="$(psql_run -tA -c "SELECT (SELECT count(*) FROM orders) || ' orders, ' || (SELECT count(*) FROM contract_note) || ' contract notes, ' || (SELECT count(*) FROM import_run) || ' imports'")"
echo "Database: $MODE"
echo "Contains: $COUNT"

if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "Delete ALL of this data? Type YES to continue: " answer
  [[ "$answer" == "YES" ]] || { echo "Nothing deleted."; exit 0; }
fi

mkdir -p backups
BACKUP="backups/before-reset-$(date +%Y%m%d-%H%M%S).sql"
dump_run > "$BACKUP"
echo "Backup written to $BACKUP"

psql_run -q -c "$SQL"
echo "Database cleared. Reload the page in the browser (the app can keep running)."
echo "To restore the backup: see README, section 'Backups'."
