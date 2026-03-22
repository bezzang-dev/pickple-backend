#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "============================================"
echo "  Pickple Backend - Stopping Local Environment"
echo "============================================"
echo ""

# Stop services first
echo "[1/2] Stopping microservices..."
docker compose -f "$PROJECT_ROOT/docker/docker-compose.service.yml" --env-file "$PROJECT_ROOT/docker/dev.env" down 2>/dev/null || true

# Stop infrastructure
echo "[2/2] Stopping infrastructure..."
docker compose -f "$PROJECT_ROOT/docker/docker-compose.dev.yml" --env-file "$PROJECT_ROOT/docker/dev.env" down

echo ""
echo "All containers stopped."
echo ""
echo "To also remove volumes (database data), run:"
echo "  docker compose -f docker/docker-compose.dev.yml --env-file docker/dev.env down -v"
echo ""
