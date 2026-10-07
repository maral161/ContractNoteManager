#!/usr/bin/env bash
# Starts ContractNoteManager on this Mac: database, build (first time or with --build), app.
# Then open http://localhost:8080
set -euo pipefail
cd "$(dirname "$0")"

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

docker_ready() {
  command -v docker >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 && return 0
  echo "Docker Desktop is not running – starting it…"
  open -a Docker 2>/dev/null || return 1
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && return 0; sleep 2; done
  return 1
}

if docker_ready; then
  # The app's own database in Docker, on port 5433 (5432 may be used by another PostgreSQL)
  docker compose up -d db
  echo "Waiting for the database…"
  for _ in $(seq 1 30); do port_open 5433 && break; sleep 1; done
elif port_open 5432; then
  echo "Docker is not available – using the PostgreSQL running on localhost:5432."
  export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/contractnotemanager}"
else
  echo "No database: start Docker Desktop (wait for 'Engine running') or a PostgreSQL on port 5432,"
  echo "then run ./start.sh again. See README section 1."
  exit 1
fi

JAR=backend/target/contractnotemanager.jar
if [[ "${1:-}" == "--build" || ! -f "$JAR" ]]; then
  NODE_VERSION="$(node -v 2>/dev/null || echo none)"
  if ! node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit((a===20&&b>=19)||(a===22&&b>=12)||a>=23?0:1)' 2>/dev/null; then
    echo "Node.js $NODE_VERSION is too old for the UI build: Node 20.19+ or 22.12+ is needed."
    echo "Install a current one (brew install node, or nvm install 22), then run:"
    echo "  rm -rf frontend/node_modules && ./start.sh --build"
    exit 1
  fi
  echo "Building the UI and the backend…"
  (cd frontend && npm install --no-audit --no-fund && npm run build)
  (cd backend && ./mvnw -q package -DskipTests)
fi

cd backend   # application-local.yml is read from here
exec java -jar target/contractnotemanager.jar
