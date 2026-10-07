#!/usr/bin/env bash
# Starts ContractNoteManager on this Mac: database, build (first time or with --build), app.
# Then open http://localhost:8080
set -euo pipefail
cd "$(dirname "$0")"

db_reachable() { (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; }

if db_reachable && ! (command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1); then
  echo "Using the PostgreSQL that is already running on localhost:5432."
elif command -v docker >/dev/null 2>&1; then
  if ! docker info >/dev/null 2>&1; then
    echo "Docker Desktop is not running – starting it…"
    open -a Docker 2>/dev/null || true
    for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
    if ! docker info >/dev/null 2>&1; then
      echo "Docker Desktop did not start. Open it, wait for 'Engine running', and run ./start.sh again."
      echo "(Or use a Homebrew PostgreSQL instead, see README section 1.)"
      exit 1
    fi
  fi
  docker compose up -d db
  echo "Waiting for the database…"
  for _ in $(seq 1 30); do db_reachable && break; sleep 1; done
else
  echo "Docker not found – make sure PostgreSQL is running on localhost:5432 (see README)."
fi

JAR=backend/target/contractnotemanager.jar
if [[ "${1:-}" == "--build" || ! -f "$JAR" ]]; then
  echo "Building the UI and the backend…"
  (cd frontend && npm install --no-audit --no-fund && npm run build)
  (cd backend && ./mvnw -q package -DskipTests)
fi

cd backend   # application-local.yml is read from here
exec java -jar target/contractnotemanager.jar
