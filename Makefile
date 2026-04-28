OPEN_CMD := open

.PHONY: start
start:  ## start all gc-visualizer containers
	@docker compose up -d --remove-orphans

.PHONY: stop
stop: ## Stop all gc-visualizer containers
	@docker compose down --remove-orphans

.PHONY: gc-visualizer build
gc-visualizer: ## executes a docker build for gc-visualizer.
	@./mvnw clean package -DskipTests; \
    docker build . -t gc-visualizer:latest;

build: gc-visualizer

.PHONY: all-logs
all-logs: ## Attach to the firehose of logs
	@docker compose logs -f

.PHONY: status
status: ## View status of services
	@docker compose ps --all --format "{{.Service}}|{{.Status}}" | column -t -s '|'

.PHONY: select-service
select-service: # Select a service using fzf, outputs to stdout
	@docker compose ps --all --format "{{.Service}}|{{.Status}}" | column -t -s '|' | fzf --height 40% --reverse | awk '{print $$1}'

.PHONY: wipe-logs
wipe-logs: ## deletes the logs
	@echo "✓ Wiping logs like a boss"; \
	rm -rf ./logs/*; \


.PHONY: logs
logs: ## Select a service and view its logs\nalternatively, run make logs:servicename to skip the selection step
	@if [ "$(word 2,$(MAKECMDGOALS))" != "" ]; then \
		docker compose logs -f $(word 2,$(MAKECMDGOALS)); \
	else \
		docker compose logs -f $$(make select-service); \
	fi

.PHONY: open-ui
open-ui: ## Open all 3 GC dashboards in browser tabs
	@echo "🚀 Opening G1GC (8081), Gen-ZGC (8082), and Plain-ZGC (8083)..."
	@$(OPEN_CMD) http://localhost:8081
	@$(OPEN_CMD) http://localhost:8082
	@$(OPEN_CMD) http://localhost:8083

.PHONY: open-grafana
open-grafana: ## Open Grafana dashboard (admin/gcviz)
	@echo "📊 Opening Grafana at http://localhost:3000 (admin/gcviz)..."
	@$(OPEN_CMD) http://localhost:3000

.PHONY: open-all
open-all: open-ui open-grafana ## Open all 3 GC dashboards + Grafana

.PHONY: k6
k6: ## Run k6 load test — 1min HIGH mode + spikes on all 3 GC apps
	@k6 run k6/gc-stress.js

# Based on http://marmelab.com/blog/2016/02/29/auto-documented-makefile.html
help: ## Print help for each make target
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {gsub(/\\n/, "\n" sprintf("%26s", " "));printf "\033[36m%-25s\033[0m %s\n\n", $$1, $$2}'
