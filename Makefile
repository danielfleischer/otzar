.PHONY: build client install check-server server-deps run stop status test test-client test-server clean clear-index

INSTALL_DIR ?= $(HOME)/.local/bin

build: client ## Build the Go client into bin/otzer

client: ## Compile the Go CLI
	go build -C client -ldflags "-X 'otzer/internal/lifecycle.serverDir=$(CURDIR)/server'" -o ../bin/otzer .

install: build ## Install the client binary to ~/.local/bin (override with INSTALL_DIR=)
	@mkdir -p $(INSTALL_DIR)
	rm -f $(INSTALL_DIR)/otzer
	cp bin/otzer $(INSTALL_DIR)/otzer
	@echo "installed $(INSTALL_DIR)/otzer"

check-server: ## Compile-check every server namespace (no boot, no artifact)
	cd server && clojure -M:check -e "(run! require '[rag.config rag.main rag.http rag.index rag.embed rag.index.walker rag.index.extractor rag.index.extract.pdf rag.index.segmenter rag.index.chunker rag.index.lucene rag.query.hybrid]) (println \"server namespaces compiled OK\")"

server-deps: ## Pre-fetch the server's JVM dependencies
	cd server && clojure -P

run: build ## Build the client and start the server
	./bin/otzer server start

stop: ## Stop the server
	./bin/otzer server stop

status: ## Show server status
	./bin/otzer server status

test: test-client test-server ## Run all tests

test-client: ## Run Go tests
	go test -C client ./...

test-server: check-server ## Compile-check then run Clojure tests
	cd server && clojure -M:test

clean: ## Remove build artifacts
	rm -rf bin

clear-index: ## Stop the server if running, then delete the on-disk index (~/.otzer/index)
	@./bin/otzer server stop 2>/dev/null || true
	rm -rf ~/.otzer/index
	@echo "index cleared"
