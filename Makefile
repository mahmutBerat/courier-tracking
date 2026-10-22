.DEFAULT_GOAL := help

export DB_PORT ?= 5432
export DB_NAME ?= courier_tracking
export DB_USER ?= courier_tracking
export DB_PASSWORD ?= courier_tracking_local

.PHONY: help up down status logs db-shell run stack-up stack-logs test build clean

help:
	@printf '%s\n' \
		'make run         Wait for PostgreSQL and run the application with the local profile' \
		'make up          Start PostgreSQL and wait until it is ready' \
		'make stack-up    Build the application image and start PostgreSQL and the application' \
		'make stack-logs  Follow application logs from the Compose stack' \
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
	docker compose down

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
