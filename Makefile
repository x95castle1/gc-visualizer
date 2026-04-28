.PHONY: start
start:  ## start all gc-visualizer containers
	@docker compose up -d --remove-orphans

.PHONY: stop
stop: ## Stop all gc-visualizer containers
	@docker compose down --remove-orphans

.PHONY: gc-visualizer
gc-visualizer: ## executes a docker build for gc-visualizer.
	@docker build . -t gc-visualizer:latest; \

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

# Based on http://marmelab.com/blog/2016/02/29/auto-documented-makefile.html
help: ## Print help for each make target
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {gsub(/\\n/, "\n" sprintf("%26s", " "));printf "\033[36m%-25s\033[0m %s\n\n", $$1, $$2}'
