#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "============================================"
echo "  Pickple Backend - Local Environment Setup"
echo "============================================"
echo ""

# Step 1: Start infrastructure
echo "[1/4] Starting infrastructure containers (DB, Kafka, Redis, Monitoring)..."
docker compose -f "$PROJECT_ROOT/docker/docker-compose.dev.yml" --env-file "$PROJECT_ROOT/docker/dev.env" up -d

# Step 2: Wait for infrastructure readiness
echo ""
echo "[2/4] Waiting for infrastructure to be ready..."
echo "  - Waiting for PostgreSQL (user_db)..."
until docker exec user_db pg_isready -U user_db -q 2>/dev/null; do sleep 1; done
echo "    user_db: ready"

echo "  - Waiting for PostgreSQL (commerce_db)..."
until docker exec commerce_db pg_isready -U commerce_db -q 2>/dev/null; do sleep 1; done
echo "    commerce_db: ready"

echo "  - Waiting for PostgreSQL (payment_db)..."
until docker exec payment_db pg_isready -U pickple -q 2>/dev/null; do sleep 1; done
echo "    payment_db: ready"

echo "  - Waiting for PostgreSQL (notification_db)..."
until docker exec notification_db pg_isready -U pickple -q 2>/dev/null; do sleep 1; done
echo "    notification_db: ready"

echo "  - Waiting for Kafka..."
until docker exec kafka kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1; do sleep 2; done
echo "    kafka: ready"

echo "  - Waiting for Redis..."
until docker exec redis-master redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done
echo "    redis: ready"

echo ""
echo "  All infrastructure is ready!"

# Step 3: Build and start services
echo ""
echo "[3/4] Building and starting microservices (this may take a few minutes on first run)..."
docker compose -f "$PROJECT_ROOT/docker/docker-compose.service.yml" --env-file "$PROJECT_ROOT/docker/dev.env" up -d --build

# Step 4: Summary
echo ""
echo "[4/4] Waiting for Eureka to register all services..."
sleep 15

echo ""
echo "============================================"
echo "  All services are starting up!"
echo "============================================"
echo ""
echo "  Service URLs:"
echo "    Gateway (API):     http://localhost:19091"
echo "    Eureka Dashboard:  http://localhost:19090"
echo ""
echo "  Monitoring URLs:"
echo "    Grafana:           http://localhost:3000  (admin / pickple)"
echo "    Prometheus:        http://localhost:9090"
echo "    Zipkin:            http://localhost:9411"
echo "    Kafka UI:          http://localhost:8080"
echo ""
echo "  Use 'docker compose -f docker/docker-compose.service.yml logs -f' to follow service logs"
echo ""
