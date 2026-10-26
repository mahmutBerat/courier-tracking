# Local development guide

Setup, configuration, commands, migrations, and troubleshooting. For the project
overview and review reading order, see the [project README](../README.md).
Run the commands below from the repository root.

## Requirements

- JDK 25 (check with `java -version`).
- A running Docker Engine or Docker Desktop.
- Docker Compose v2 with `--wait` support.
- GNU Make.

A separate Gradle installation is not required; use the Gradle Wrapper included in
the repository. The first run requires an internet connection to download Gradle
dependencies and the PostgreSQL and `apache/kafka:4.3.1` images).

## Quick start

From the project root:

```sh
make run
```

This command starts PostgreSQL 18 and a single-node Kafka broker, waits for their
health checks to pass, and runs the Spring Boot application in the terminal with the
`local` profile, which enables the Kafka ingress.
The default application address is `http://localhost:8080`.

From another terminal:

```sh
curl --fail http://localhost:8080/actuator/health
```

A healthy application's JSON response contains `"status":"UP"`. Stop the application
with `Ctrl+C`. PostgreSQL and Kafka keep running in the background. To stop them:

```sh
make down
```

Data is persisted in a named volume. Neither `make down` nor `make clean` deletes
the database. A database created before the changelog was rebased (ADR-015) must be
recreated with `docker compose down -v` before the application starts again.

## Interactive API testing

After startup, use the [demo page](http://localhost:8080/demo.html),
[Swagger UI](http://localhost:8080/swagger-ui/index.html),
[OpenAPI JSON](http://localhost:8080/v3/api-docs), or the
[curl walkthrough](api-examples.md). Substitute your configured server port.
Register a courier before submitting locations. Distance GET returns 200 with the
persisted total (zero before locations), or 404 for an unknown courier. Swagger documentation uses springdoc-openapi 3.1.1.

## Commands

| Command | Description |
|---|---|
| `make` / `make help` | List available commands |
| `make up` | Start PostgreSQL and Kafka and wait until they are ready |
| `make run` | Start PostgreSQL and Kafka, then run the application |
| `make stack-up` | Build the application image and start PostgreSQL, Kafka, and the application in containers |
| `make stack-logs` | Follow application logs from the Compose stack |
| `make down` | Remove Compose services while preserving data |
| `make status` | Show Compose service status |
| `make logs` | Follow PostgreSQL and Kafka logs |
| `make db-shell` | Open the `psql` console (exit with `\q`) |
| `make test` | Run tests and generate the JaCoCo coverage report |
| `make build` | Run tests and package JAR files under `build/libs/` |
| `make clean` | Remove Gradle build outputs |
| `make kafka-send` | Register `kafka-courier-1` over HTTP (201 or 409) and produce `scripts/kafka/sample-locations.jsonl` |
| `make kafka-send-invalid` | Produce `scripts/kafka/invalid-locations.jsonl`; the application skips all three lines and logs each once at INFO |

## Local configuration

| Variable | Default |
|---|---|
| `DB_PORT` | `5432` |
| `DB_NAME` | `courier_tracking` |
| `DB_USER` | `courier_tracking` |
| `DB_PASSWORD` | `courier_tracking_local` |
| `SERVER_PORT` | `8080` |
| `KAFKA_PORT` | `9092` (host port of the Compose broker) |

The PostgreSQL and application ports are published only on `127.0.0.1`. Default
credentials are for local development. Make variables are passed to both Compose
and the application.
For example, if another PostgreSQL instance already uses port 5432:

```sh
make run DB_PORT=5433 SERVER_PORT=8081
```

You can also export settings in your shell for use throughout the session:

```sh
export DB_PORT=5433
make up
make run
```

PostgreSQL user, password, and database settings are applied only when an empty
volume is initialized for the first time. Changing these variables with an existing
volume does not change the database user or password.

To run from IntelliJ, first run `make up`, then run `CourierTrackingApplication`.
No profile configuration is required: `local` is the default when no active profile
is explicitly selected. Selecting another active profile replaces this fallback.
If you use custom `DB_*` values, add the same values to the IDE run configuration's
environment variables.

## Running the stack in containers

```sh
make stack-up
```

This builds the application image with the multi-stage `Dockerfile` (Gradle build
on a Temurin 25 JDK image, runtime on the JRE image as a non-root user) and starts
the `postgres`, `kafka`, and `app` Compose services. The application reaches PostgreSQL over
the Compose network through `SPRING_DATASOURCE_*` environment variables, which
override the `local` profile's localhost settings. It listens on
`http://localhost:${SERVER_PORT:-8080}`. Use `make stack-logs` to follow startup and
`make down` to stop the services. Rebuilding after code changes requires
`make stack-up` again; `make run` continues to work independently because it
does not start the `app` service.

## Kafka ingress

The Kafka ingress (S010, ADR-013) is off by default: `courier.kafka.enabled=false`
in `application.properties`. The `local` profile (`application-local.properties`)
turns it on and sets the connection defaults; there is no separate `kafka` Spring
profile. Because `local` is also the default profile, every local run and every
Spring Boot integration test starts the consumer and contacts `localhost:9092`
unless the flag is overridden; without a broker the application still starts, only
slower (see the end of this section).

| Property | Value |
|---|---|
| `courier.kafka.enabled` | `true` |
| `courier.kafka.topic` | `courier-locations` |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `spring.kafka.consumer.group-id` | `courier-tracking` |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` |
| key and value (de)serializers | `String` |

`make run` starts PostgreSQL and the broker and runs the application with the
`local` profile. The broker is
`apache/kafka:4.3.1` in single-node KRaft mode, reachable from the host on
`localhost:${KAFKA_PORT:-9092}` and from the `app` container on `kafka:29092`; the
application declares the topic with one partition at startup (S010-D12). The Make targets
and the producer script run the console tools inside the broker container on
`kafka:29092`, and `make run` passes `KAFKA_BOOTSTRAP_SERVERS=localhost:$(KAFKA_PORT)`
to the application, so overriding `KAFKA_PORT` keeps everything consistent. Broker
data lives in the `kafka_data` volume (`KAFKA_LOG_DIRS=/var/lib/kafka/data`). From IntelliJ,
run `make up` first; the default run configuration (profile `local`) already
enables the ingress. Listener concurrency can be raised with
`spring.kafka.listener.concurrency` (one consumer thread per partition at most).

`make kafka-send` registers `kafka-courier-1` through the HTTP API (a `409` on a
second run is fine) and pipes `scripts/kafka/sample-locations.jsonl` through the
broker's console producer; lines are `key|json`. Re-running it reproduces exact
duplicates, which the database suppresses, so the distance does not change.
`make kafka-send-invalid` produces a malformed line, an out-of-range latitude, and
an unregistered courier. None of them can succeed on retry, so the application
skips each one at once and logs a single INFO line with topic, partition, offset,
key, and the root exception class (`Skipped unprocessable location message ...`);
the payload is never logged. Transient failures (for example a database outage)
are attempted three times in total (two retries, one second apart) before the
record is skipped the same way. Publishing skipped records to a dead-letter topic is the planned next step
(S010-D14); the recoverer bean in `CourierLocationKafkaConfiguration` is where it
plugs in.

`make stack-up` builds the image and starts `postgres`, `kafka`, and `app`.
`make down` removes the Compose services and keeps the `postgres_data` and `kafka_data` volumes.

If the application starts while the broker is down, startup is
slower (the admin client retries topic creation for about 40 s) but succeeds; the
consumer joins as soon as the broker is reachable and reads the backlog.

## Tests and migrations

```sh
make test
```

Docker must be running. The existing context test starts a separate PostgreSQL 18
container through Testcontainers; it does not use the Compose database and does not
require `make up`. Testcontainers supplies the connection details and temporary
port automatically. Location submission integration tests verify committed input
records through the HTTP endpoint and the real generated MapStruct mapper.

The `test` task finalizes with `jacocoTestReport`. The HTML report is at
`build/reports/jacoco/test/html/index.html` and the XML at
`build/reports/jacoco/test/jacocoTestReport.xml`; the XML feeds the CI quality
summary. No coverage threshold is enforced.

## Continuous integration

`.github/workflows/ci.yml` runs `./gradlew clean build` on pushes to `main`, pull
requests, and manual dispatch using Temurin 25 and the Gradle action. The runner's
Docker daemon serves Testcontainers. `.github/scripts/quality-summary.py` turns the
JUnit XML and JaCoCo XML into a Markdown summary that is appended to the run page
and, for pull requests from this repository, kept as one updated comment. Test and
coverage reports are uploaded as the `verification-reports` artifact for 14 days.

The Liquibase changelog is located at
`src/main/resources/db/changelog/db.changelog-master.xml`.
All migrations live in this single XML as separate changeSets containing native
SQL. The changelog is a create-only baseline (ADR-015): one `CREATE TABLE` changeSet
per current table (`couriers`, `courier_location_inputs`, `courier_store_visits`) in its
final shape, including the `created_at`/`updated_at` audit columns every table
carries. Automatic Hibernate schema generation is disabled. Input times are
truncated to milliseconds in the application and stored as
`TIMESTAMP(3) WITH TIME ZONE`. Coordinates retain the existing Double
representation. Future schema changes are added as incremental changeSets after
the baseline.

## Troubleshooting

- Docker connection error: start Docker Desktop/Engine and check with `docker info`.
- Port already in use: change `DB_PORT` or `SERVER_PORT`.
- Database does not become ready: inspect `make status` and `make logs`.
- Java toolchain error: check your JDK 25 installation and `JAVA_HOME`.

References: the [official Docker image documentation](https://hub.docker.com/_/postgres)
for the PostgreSQL 18 volume layout, and the
[Spring Boot properties reference](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
for Spring configuration.

