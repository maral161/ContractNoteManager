#!/usr/bin/env bash
# Starts ContractNoteManager on this Mac: database, build (first time or with --build), app.
# Then open http://localhost:8080
set -euo pipefail
cd "$(dirname "$0")"

if command -v docker >/dev/null 2>&1; then
  docker compose up -d db
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
