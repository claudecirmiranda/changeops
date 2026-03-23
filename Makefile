# ─────────────────────────────────────────────────────────────────────────────
#  ChangeOps Dashboard — Makefile
#  Usage: make <target>
# ─────────────────────────────────────────────────────────────────────────────

.PHONY: help up down clean restart logs \\
        build build-backend build-frontend \
        test test-backend test-frontend \
        lint lint-backend lint-frontend \
        clean db-shell kafka-shell \
        smoke publish-deploy-event

# ── Colours ───────────────────────────────────────────────────────────────────
CYAN  = \033[0;36m
RESET = \033[0m
BOLD  = \033[1m

help: ## Show this help
	@echo ""
	@echo "$(BOLD)ChangeOps Dashboard$(RESET)"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-28s$(RESET) %s\n", $$1, $$2}'
	@echo ""

# ── Stack lifecycle ───────────────────────────────────────────────────────────
up: ## Start the full local stack (infra + services)
	docker compose up -d --build
	@echo ""
	@echo "$(BOLD)Stack is up:$(RESET)"
	@echo "  change-service     → http://localhost:8080"
	@echo "  deploy-orchestrator → http://localhost:8081"
	@echo "  Swagger UI          → http://localhost:8080/swagger-ui.html"
	@echo "  Kafka UI            → http://localhost:8090"
	@echo "  Prometheus          → http://localhost:9090"
	@echo "  Grafana             → http://localhost:3001  (admin/changeops)"
	@echo ""

down: ## Stop and remove all containers (preserves data volumes)
	docker compose down

clean: ## Stop and remove everything INCLUDING data volumes
	docker compose down -v

restart: ## Restart all services
	docker compose restart

logs: ## Tail logs from all services
	docker compose logs -f change-service deploy-orchestrator

logs-cs: ## Tail change-service logs
	docker compose logs -f change-service

logs-do: ## Tail deploy-orchestrator logs
	docker compose logs -f deploy-orchestrator

ps: ## Show container status
	docker compose ps

# ── Build ─────────────────────────────────────────────────────────────────────
build: build-backend build-frontend ## Build all

build-backend: ## Build both backend services (skip tests)
	cd backend/change-service    && mvn clean package -DskipTests -q
	cd backend/deploy-orchestrator && mvn clean package -DskipTests -q
	@echo "$(CYAN)Backend build complete$(RESET)"

build-frontend: ## Build frontend for production
	cd frontend && npm ci && npm run build
	@echo "$(CYAN)Frontend build complete$(RESET)"

# ── Test ──────────────────────────────────────────────────────────────────────
test: test-backend test-frontend ## Run all tests

test-backend: ## Run backend unit + integration tests
	cd backend/change-service      && mvn test
	cd backend/deploy-orchestrator && mvn test

test-backend-unit: ## Run only unit tests (fast)
	cd backend/change-service      && mvn test -Dtest="**/*Test" -DfailIfNoTests=false
	cd backend/deploy-orchestrator && mvn test -Dtest="**/*Test" -DfailIfNoTests=false

test-backend-it: ## Run only integration tests (Testcontainers — needs Docker)
	cd backend/change-service && mvn test -Dtest="**/*IT" -DfailIfNoTests=false

test-frontend: ## Run frontend unit tests
	cd frontend && npm test

test-frontend-watch: ## Run frontend tests in watch mode
	cd frontend && npm run test:watch

test-frontend-coverage: ## Run frontend tests with coverage
	cd frontend && npm run test:coverage

# ── Lint ──────────────────────────────────────────────────────────────────────
lint: lint-backend lint-frontend ## Lint all

lint-backend: ## Check backend code style (spotless / checkstyle)
	cd backend/change-service      && mvn spotless:check || true
	cd backend/deploy-orchestrator && mvn spotless:check || true

lint-frontend: ## Lint frontend TypeScript
	cd frontend && npm run lint

lint-frontend-fix: ## Auto-fix frontend lint issues
	cd frontend && npm run lint:fix

# ── Dev helpers ───────────────────────────────────────────────────────────────
install-frontend: ## Install frontend npm dependencies
	cd frontend && npm install

db-shell: ## Open a psql shell in the Postgres container
	docker compose exec postgres psql -U changeops -d changeops

kafka-shell: ## Open a Kafka CLI shell
	docker compose exec kafka bash

kafka-topics: ## List all Kafka topics
	docker compose exec kafka kafka-topics \
		--bootstrap-server localhost:9092 --list

# ── Smoke test ────────────────────────────────────────────────────────────────
smoke: ## Create a sample change via curl (requires stack running)
	@echo "Creating a change request..."
	@curl -sS -X POST http://localhost:8080/api/v1/changes \
		-H "Content-Type: application/json" \
		-H "X-User-Id: dev-user-001" \
		-d '{ \
			"title": "Smoke test change", \
			"description": "Automated smoke test", \
			"componentId": "smoke-service", \
			"requestedBy": "dev-user-001", \
			"scheduledAt": "2099-12-31T00:00:00Z" \
		}' | python3 -m json.tool
	@echo ""

publish-deploy-event: ## Publish a DeployFinishedEvent (SUCCESS) to Kafka
	@echo "Publishing DeployFinishedEvent..."
	@CHANGE_ID=$${CHANGE_ID:-11111111-1111-1111-1111-111111111111}; \
	DEPLOY_ID=$$(python3 -c "import uuid; print(uuid.uuid4())"); \
	PAYLOAD="{\"eventType\":\"DeployFinishedEvent\",\"version\":\"1.0\",\
	\"correlationId\":\"$$(python3 -c 'import uuid; print(uuid.uuid4())')\",\
	\"occurredAt\":\"$$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\
	\"payload\":{\"deployId\":\"$$DEPLOY_ID\",\"changeId\":\"$$CHANGE_ID\",\
	\"result\":\"$${RESULT:-SUCCESS}\",\"executedAt\":\"$$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}}"; \
	echo $$PAYLOAD | docker compose exec -T kafka \
		kafka-console-producer \
		--bootstrap-server localhost:9092 \
		--topic changeops.deploy.finished \
		--property "parse.key=false"; \
	echo "Published deployId=$$DEPLOY_ID for changeId=$$CHANGE_ID"

# ── Clean ─────────────────────────────────────────────────────────────────────
clean: ## Remove build artifacts
	cd backend/change-service      && mvn clean -q
	cd backend/deploy-orchestrator && mvn clean -q
	rm -rf frontend/dist frontend/node_modules/.vite

clean-all: clean down ## Remove build artifacts and stop containers
