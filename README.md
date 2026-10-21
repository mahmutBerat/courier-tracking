# Courier Tracking

A Java REST API project for tracking courier locations, store entries, and total
travel distance. The project skeleton and local development infrastructure are
ready. POST location submission persists inputs; distance and store-entry processing
are not implemented yet.
Decisions and open questions are recorded in [initial-decisions.md](docs/initial-decisions.md).
See the [documentation index](docs/README.md) for the specification workflow and
the [development roadmap](docs/roadmap.md) for the proposed sequence.

All project code, comments, documentation, and command descriptions must be written
in English. Proper names from the supplied store data retain their original spelling.

## Requirements

- JDK 25 (check with `java -version`).
- A running Docker Engine or Docker Desktop.
- Docker Compose v2 with `--wait` support.
- GNU Make.

A separate Gradle installation is not required; use the Gradle Wrapper included in
the repository. The first run requires an internet connection to download Gradle
dependencies and the PostgreSQL image.

## Quick start

From the project root:

```sh
make run
```

This command starts PostgreSQL 18, waits for its health check to pass, and runs the
Spring Boot application in the terminal with the `local` profile.
The default application address is `http://localhost:8080`.

From another terminal:

```sh
curl --fail http://localhost:8080/actuator/health
```

A healthy application's JSON response contains `"status":"UP"`. Stop the application
with `Ctrl+C`. PostgreSQL keeps running in the background. To stop it:

```sh
make down
```

Data is persisted in a named volume. Neither `make down` nor `make clean` deletes
the database.

## Commands

| Command | Description |
|---|---|
| `make` / `make help` | List available commands |
| `make up` | Start PostgreSQL and wait until it is ready |
| `make run` | Prepare PostgreSQL and run the application |
| `make down` | Remove Compose services while preserving data |
| `make status` | Show Compose service status |
| `make logs` | Follow PostgreSQL logs |
| `make db-shell` | Open the `psql` console (exit with `\q`) |
| `make test` | Run tests |
| `make build` | Run tests and package JAR files under `build/libs/` |
| `make clean` | Remove Gradle build outputs |

## Local configuration

| Variable | Default |
|---|---|
| `DB_PORT` | `5432` |
| `DB_NAME` | `courier_tracking` |
| `DB_USER` | `courier_tracking` |
| `DB_PASSWORD` | `courier_tracking_local` |
| `SERVER_PORT` | `8080` |

The PostgreSQL port is published only on `127.0.0.1`. Default credentials are for
local development. Make variables are passed to both Compose and the application.
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

## Tests and migrations

```sh
make test
```

Docker must be running. The existing context test starts a separate PostgreSQL 18
container through Testcontainers; it does not use the Compose database and does not
require `make up`. Testcontainers supplies the connection details and temporary
port automatically. Location submission integration tests verify committed input
records through the HTTP endpoint and the real generated MapStruct mapper.

The Liquibase changelog is located at
`src/main/resources/db/changelog/db.changelog-master.xml`.
The first migration creates `courier_location_inputs` for traceability. Automatic
Hibernate schema generation is disabled. Input timestamps use PostgreSQL
microsecond precision; coordinates retain the existing Double representation.

## Troubleshooting

- Docker connection error: start Docker Desktop/Engine and check with `docker info`.
- Port already in use: change `DB_PORT` or `SERVER_PORT`.
- Database does not become ready: inspect `make status` and `make logs`.
- Java toolchain error: check your JDK 25 installation and `JAVA_HOME`.

References: the [official Docker image documentation](https://hub.docker.com/_/postgres)
for the PostgreSQL 18 volume layout, and the
[Spring Boot properties reference](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
for Spring configuration.

## API controller increment

```sh
curl -i -X POST http://localhost:8080/api/v1/couriers/locations \
  -H 'Content-Type: application/json' \
  -d '{"time":"2026-10-21T12:00:00Z","courier":"courier-1","lat":40.9923307,"lng":29.1244229}'

curl -i 'http://localhost:8080/api/v1/couriers/distance?courierId=courier-1'
```

All POST fields are required. `time` represents a UTC instant; courier identifiers
are nonblank strings. GET requires the `courierId` query parameter.
The accepted response contract is POST `204 No Content`, GET `200` with
`{"courierId":"courier-1","distanceMeters":1234.56}`, and `404` for unknown couriers.
These outcomes are verified with a test backend.

Valid POST requests now return `204` after the input record is committed. Each
accepted submission creates a trace record; distance accumulation and store-entry
processing are TODOs in the service. GET still returns `501 Not Implemented` with
Problem Details, even for a courier with stored inputs. See
[S004](docs/specs/004-location-submission.md) for this intermediate scope.
Binding/required-field errors return `400`; non-JSON POST requests return
`415`. The contract and remaining validation policies are documented in
[S002](docs/specs/002-api-contract-and-controllers.md).

Run API binding and controller tests without Docker:

```sh
./gradlew test --tests '*CourierControllerTests' --tests '*CourierLocationInputJsonTests' --tests '*CourierServiceAvailabilityTests'
```

The tests use Spring Boot's [MVC test slice](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html).

## Lombok

Lombok is configured as a compile-only dependency and annotation processor for
main and test code, using the version managed by Spring Boot. Controllers use
`@RequiredArgsConstructor` with final service fields. The controller calls the
concrete `CourierService`, whose required repository and mapper dependencies also
use Lombok constructor injection.

Reload the Gradle project in IntelliJ after pulling build changes. The Gradle
build handles annotation processing using the
[official Lombok setup](https://projectlombok.org/setup/gradle).

## MapStruct

Entity/DTO conversions use MapStruct 1.6.3 with `lombok-mapstruct-binding` 0.2.0.
All mappers use `BaseMapperConfig`: Spring components, constructor injection, and
compile-time errors for unmapped target properties. Inject mappers instead of
creating static instances. Gradle generates implementations during compilation.

`CourierMapper` creates the total distance response. `CourierLocationMapper` maps
API input to the persisted trace record, explicitly renaming coordinate/courier
fields and ignoring the generated row ID. Reload the Gradle project in
IntelliJ after this dependency change. See the
[MapStruct Lombok integration guide](https://mapstruct.org/documentation/stable/reference/html/#lombok).

## Package organization

Courier controllers, API records, and mappers live in `courier.api`; the concrete
service lives in `courier.application`. Shared mapper configuration stays in
`common.mapping`. Test packages mirror the feature packages.

Input history entity, repository, and mapper live in `courier.persistence`.
Courier domain and store domain/persistence packages will be created when needed. See [S003](docs/specs/003-application-structure.md).
