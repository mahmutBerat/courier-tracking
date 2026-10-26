.DEFAULT_GOAL := help

export DB_PORT ?= 5432
export DB_NAME ?= courier_tracking
export DB_USER ?= courier_tracking
export DB_PASSWORD ?= courier_tracking_local
export SERVER_PORT ?= 8080
export KAFKA_PORT ?= 9092
# bootstrap address for make run-kafka; the app container uses kafka:29092 (compose.yaml)
export KAFKA_BOOTSTRAP_SERVERS ?= localhost:$(KAFKA_PORT)

.PHONY: help up down status logs db-shell run stack-up stack-logs test build clean kafka-up run-kafka stack-up-kafka kafka-send kafka-send-invalid

help:
	@printf '%s\n' \
		'make run         Wait for PostgreSQL and run the application with the local profile' \
		'make up          Start PostgreSQL and wait until it is ready' \
		'make stack-up    Build the application image and start PostgreSQL and the application' \
		'make stack-logs  Follow application logs from the Compose stack' \
		'make kafka-up    Start the local Kafka broker (Compose profile kafka)' \
		'make run-kafka   Run the application with the local and kafka profiles' \
		'make stack-up-kafka  Build and start PostgreSQL, Kafka, and the application with the kafka profile' \
		'make kafka-send  Register kafka-courier-1 and produce the sample location messages' \
		'make kafka-send-invalid  Produce three unprocessable messages; the application skips and logs them' \
		'make down        Remove the services while preserving data' \
		'make status      Show service status' \
		'make logs        Follow PostgreSQL logs' \
		'make db-shell    Open the PostgreSQL psql console' \
		'make test        Run tests with an independent Testcontainers database and a JaCoCo report' \
		'make build       Run tests and package the application' \
		'make clean       Remove Gradle build outputs'

up:
	docker compose up -d --wait --wait-timeout 90 postgres

stack-up:
	docker compose up -d --build --wait --wait-timeout 180

stack-logs:
	docker compose logs -f app

down:
	docker compose --profile kafka down

kafka-up:
	docker compose --profile kafka up -d --wait --wait-timeout 120 kafka

run-kafka: up kafka-up
	./gradlew bootRun --args='--spring.profiles.active=local,kafka'

stack-up-kafka:
	SPRING_PROFILES_ACTIVE=local,kafka docker compose --profile kafka up -d --build --wait --wait-timeout 180

kafka-send:
	@code=$$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:$(SERVER_PORT)/api/v1/couriers" \
		-H 'Content-Type: application/json' -d '{"courierId":"kafka-courier-1"}'); \
	echo "register kafka-courier-1: HTTP $$code"; \
	case "$$code" in 201|409) ;; *) echo "registration failed; is the application running on port $(SERVER_PORT)?"; exit 1;; esac
	scripts/kafka/produce-locations.sh scripts/kafka/sample-locations.jsonl

kafka-send-invalid:
	scripts/kafka/produce-locations.sh scripts/kafka/invalid-locations.jsonl

status:
	docker compose ps

logs:
	docker compose logs -f postgres

db-shell:
	docker compose exec postgres sh -c 'exec psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"'

run: up
	./gradlew bootRun --args='--spring.profiles.active=local'

test:
	./gradlew test

build:
	./gradlew build

clean:
	./gradlew clean
