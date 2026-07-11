.PHONY: build client install check-server server-deps run stop status test test-client test-server clean clear-index

INSTALL_DIR ?= $(HOME)/.local/bin

build: client ## Build the Go client into bin/myrag

client: ## Compile the Go CLI
	go build -C client -ldflags "-X 'myrag/internal/lifecycle.serverDir=$(CURDIR)/server'" -o ../bin/myrag .

install: build ## Install the client binary to ~/.local/bin (override with INSTALL_DIR=)
	@mkdir -p $(INSTALL_DIR)
	rm -f $(INSTALL_DIR)/myrag
	cp bin/myrag $(INSTALL_DIR)/myrag
	@echo "installed $(INSTALL_DIR)/myrag"

check-server: ## Compile-check every server namespace (no boot, no artifact)
	cd server && clojure -M:check -e "(run! require '[rag.config rag.main rag.http rag.index rag.embed rag.index.walker rag.index.extractor rag.index.segmenter rag.index.chunker rag.index.lucene rag.query.hybrid]) (println \"server namespaces compiled OK\")"

server-deps: ## Pre-fetch the server's JVM dependencies
	cd server && clojure -P

run: build ## Build the client and start the server
	./bin/myrag start

stop: ## Stop the server
	./bin/myrag stop

status: ## Show server status
	./bin/myrag status

test: test-client test-server ## Run all tests

test-client: ## Run Go tests
	go test -C client ./...

test-server: check-server ## Compile-check then run Clojure tests
	cd server && clojure -M:test

clean: ## Remove build artifacts
	rm -rf bin

clear-index: ## Stop the server if running, then delete the on-disk index (~/.myrag/index)
	@./bin/myrag stop 2>/dev/null || true
	rm -rf ~/.myrag/index
	@echo "index cleared"
