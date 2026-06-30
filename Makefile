.PHONY: build client server-deps run stop status test clean

build: client ## Build the Go client into bin/myrag

client: ## Compile the Go CLI
	go build -C client -o ../bin/myrag .

server-deps: ## Pre-fetch the server's JVM dependencies
	cd server && clojure -P

run: build ## Build the client and start the server
	./bin/myrag start

stop: ## Stop the server
	./bin/myrag stop

status: ## Show server status
	./bin/myrag status

test: ## Run Go tests
	go test -C client ./...

clean: ## Remove build artifacts
	rm -rf bin
