.PHONY: build client client-release uber install install-dev check-server server-deps run stop status lint test test-client test-server clean clear-index

# Where `install` puts the two pieces. Both are overridable:
#   make install INSTALL_DIR=/usr/local/bin DATA_DIR=/usr/local/share/otzar
INSTALL_DIR ?= $(HOME)/.local/bin
DATA_DIR ?= $(HOME)/.local/share/otzar
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
JAR = server/target/otzar-server.jar

build: client ## Build the Go client into bin/otzar

client: ## Compile the Go CLI
	go build -C client -ldflags "-X 'otzar/internal/lifecycle.serverDir=$(CURDIR)/server' -X 'otzar/internal/buildinfo.Version=$(VERSION)'" -o ../bin/otzar .

# Release build: no baked-in checkout path, so the installed client looks for the
# packaged jar rather than the source tree it happened to be built from. This is
# what the release workflow ships.
client-release: ## Compile the Go CLI without a baked-in server checkout
	go build -C client -ldflags "-X 'otzar/internal/buildinfo.Version=$(VERSION)'" -o ../bin/otzar .

uber: ## Package the server as one self-contained jar (server/target/otzar-server.jar)
	cd server && clojure -T:build uber

# The install a user gets from a release: client binary plus packaged server, no
# checkout needed at runtime. The client finds the jar in DATA_DIR by itself (see
# lifecycle.jarCandidates); OTZAR_SERVER_JAR overrides.
install: uber client-release ## Install the client and the server jar
	@mkdir -p $(INSTALL_DIR) $(DATA_DIR)
	rm -f $(INSTALL_DIR)/otzar $(DATA_DIR)/otzar-server.jar
	cp bin/otzar $(INSTALL_DIR)/otzar
	cp $(JAR) $(DATA_DIR)/otzar-server.jar
	@echo "installed $(INSTALL_DIR)/otzar + $(DATA_DIR)/otzar-server.jar"

# The development install: the binary keeps this checkout's server dir baked in,
# so it runs the source you're editing and needs no jar.
install-dev: build ## Install just the client, bound to this checkout
	@mkdir -p $(INSTALL_DIR)
	rm -f $(INSTALL_DIR)/otzar
	cp bin/otzar $(INSTALL_DIR)/otzar
	@echo "installed $(INSTALL_DIR)/otzar (bound to $(CURDIR)/server)"

check-server: ## Compile-check every server namespace (no boot, no artifact)
	cd server && clojure -M:check -e "(run! require '[rag.config rag.main rag.http rag.index rag.roots rag.watch rag.embed rag.index.walker rag.index.extractor rag.index.extract.pdf rag.index.extract.html rag.index.extract.docx rag.index.segmenter rag.index.chunker rag.index.lucene rag.query.hybrid]) (println \"server namespaces compiled OK\")"

server-deps: ## Pre-fetch the server's JVM dependencies
	cd server && clojure -P

run: build ## Build the client and start the server
	./bin/otzar server start

stop: ## Stop the server
	./bin/otzar server stop

status: ## Show server status
	./bin/otzar server status

lint: ## Check Go formatting (gofmt) and run go vet
	@out=$$(gofmt -l client); \
	if [ -n "$$out" ]; then \
		echo "not gofmt-clean (run 'gofmt -w' on these):"; echo "$$out"; exit 1; \
	fi
	go vet -C client ./...

test: test-client test-server ## Run all tests

test-client: ## Run Go tests
	go test -C client ./...

test-server: check-server ## Compile-check then run Clojure tests
	cd server && clojure -M:test

clean: ## Remove build artifacts
	rm -rf bin server/target

clear-index: ## Stop the server if running, then delete the on-disk index (~/.otzar/index)
	@./bin/otzar server stop 2>/dev/null || true
	rm -rf ~/.otzar/index
	@echo "index cleared"
