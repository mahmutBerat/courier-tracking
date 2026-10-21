# Courier Tracking API

A Java and Spring Boot REST API for tracking courier locations, accumulating travel
distance, and detecting entries into Migros store areas. The project uses
PostgreSQL transactions for consistent courier state and develops one API at a
time from documented decisions and acceptance examples.

Courier registration/listing, location submission, travel-distance accumulation,
and distance queries are implemented. Store catalog and store-entry processing
remain pending. This
README describes the current implementation and points reviewers to the decisions,
verification evidence, and remaining work.

## Key capabilities

- Register couriers explicitly and list registered courier IDs.
- Persist distinct location reports for registered couriers for traceability.
- Suppress exact duplicates using a database unique constraint and native
  `INSERT ... ON CONFLICT DO NOTHING`.
- Accumulate Haversine distance in meters from each courier's last processed location.
- Retain delayed and same-time distinct reports without changing travel state.
- Commit input storage and courier state together, with `SELECT FOR UPDATE` to
  serialize updates for the same courier.
- Validate coordinates and required API fields, with Problem Details for errors.
- Manage schema changes through Liquibase and verify persistence against real
  PostgreSQL using Testcontainers.

## Reviewer reading guide

| Read | What it explains |
|---|---|
| [Initial decisions](docs/initial-decisions.md) | Case study requirements, shared domain language, accepted choices, and their evolution |
| [Application structure — S003](docs/specs/003-application-structure.md) | Responsibilities, aggregate boundaries, Strategy and Repository decisions |
| [Location submission — S004](docs/specs/004-location-submission.md) | Input persistence, duplicate identity, millisecond precision, and migration evidence |
| [Travel distance — S005](docs/specs/005-travel-distance-processing.md) | Ordering examples, transaction and locking trade-offs, implementation, and test evidence |
| [API contract — S002](docs/specs/002-api-contract-and-controllers.md) | HTTP contracts and the distinction between controller tests and implemented backend behavior |
| [Courier registration — S006](docs/specs/006-courier-registration.md) | Explicit creation/listing and unknown-courier handling, superseding automatic creation |
| [Roadmap](docs/roadmap.md) | Current progress and remaining delivery scope |

Specifications distinguish proposed choices, accepted decisions, implemented
behavior, and verified behavior. For detailed API examples, mapping conventions,
and package responsibilities, see the [implementation guide](docs/implementation-guide.md).
The [documentation index](docs/README.md) also links the development workflow,
local skills, and review checklist.

## Architecture and design patterns

The application uses feature-based packages under `com.mbi.couriertracking`:

```text
courier/
  api/           Controllers, request/response records, response mapping
  application/   Use-case orchestration and transaction boundaries
  domain/        Courier travel state and ordering rules
  persistence/   Input records, Spring Data repositories, persistence mapping
common/
  geo/           Validated coordinates and distance calculation Strategy
  mapping/       Shared MapStruct configuration
```

Controllers call the concrete `CourierService`. The service coordinates persistence
and invokes `Courier`, which owns its last processed location/time and total
distance. Domain operations do not access repositories. Store packages will be
introduced with the store implementation.

| Pattern | Implementation and purpose |
|---|---|
| Strategy | `DistanceCalculatorStrategy` separates distance calculation from courier rules; `HaversineDistanceCalculatorStrategy` implements the spherical calculation |
| Repository | Spring Data JPA interfaces provide persistence operations directly, without an additional repository adapter layer |

## Processing and consistency decisions

Couriers must be registered before submitting locations. Unknown courier IDs return
`404` without storing input.

A report's identity is `(courier, time, lat, lng)`, with time truncated to
milliseconds. An exact repeat returns `204` without another input row or distance
update. Duplicate reports also acquire the courier lock. No idempotency header or cache layer is required; cache optimization is
recorded as a future TODO.

The first processed location initializes distance to zero. A strictly newer input
adds the distance from the last processed location and advances courier state.
Older or same-time distinct reports remain in input history but do not affect
calculation. Normal calculations use persisted courier state without replaying
input history.

Input insertion and courier advancement share one transaction. Courier-row locking
prevents concurrent requests from losing updates; failures roll back both writes.
The transaction has a five-second timeout. Requests are not sorted by event time,
and contention for a busy courier can cause waits or timeouts. See
[S005](docs/specs/005-travel-distance-processing.md) for the alternatives considered
and PostgreSQL concurrency tests.

Distance is the sum of Haversine segments between sampled coordinates, using a
6,371,000-meter Earth radius. It is not a road-route calculation; sampling frequency
and GPS accuracy affect the result.

## Technology stack

| Area | Technology |
|---|---|
| Runtime | Java 25, Spring Boot 4.1.1 |
| HTTP and validation | Spring Web MVC, Jakarta Validation |
| Persistence | PostgreSQL 18, Spring Data JPA, Hibernate |
| Migrations | Liquibase; native SQL changeSets in one master XML |
| Mapping and injection | MapStruct 1.6.3, Lombok |
| Health | Spring Boot Actuator |
| Testing | JUnit, Mockito, Spring Boot Test, PostgreSQL Testcontainers |
| Build and local execution | Gradle Wrapper, GNU Make, Docker Compose |

## API overview

| Method | Endpoint | Current behavior |
|---|---|---|
| POST | `/api/v1/couriers` | Register an ID; `201`, or `409` if already registered |
| GET | `/api/v1/couriers` | Sorted array of courier IDs; `[]` for an empty registry |
| POST | `/api/v1/couriers/locations` | Persist input and update travel distance; `204` after commit, including duplicates |
| GET | `/api/v1/couriers/distance?courierId=...` | `200` with persisted distance; `0` before any location; `404` for unknown couriers |
| GET | `/actuator/health` | Application health |

POST accepts `time`, `courier`, `lat`, and `lng`. Required-field or coordinate
validation failures return `400`; non-JSON POST requests return `415`.
See the [API examples and response details](docs/implementation-guide.md#api-controller-increment).

## Swagger and curl examples

After `make run`, open [Swagger UI](http://localhost:8080/swagger-ui/index.html)
to invoke every API with example payloads. The
[OpenAPI JSON](http://localhost:8080/v3/api-docs) and
[YAML](http://localhost:8080/v3/api-docs.yaml) are also available.

Follow the [curl walkthrough](docs/api-examples.md) to check health, register and
list couriers, submit locations, and call the distance endpoint. Examples include
expected statuses and error cases. Distance queries return the persisted total in meters.

## Run locally

Install JDK 25, Docker Engine/Desktop, Docker Compose v2 with `--wait` support,
and GNU Make. Start Docker, then run:

```sh
make run
```

The application runs at `http://localhost:8080`; PostgreSQL runs in Compose.
Check health from another terminal:

```sh
curl --fail http://localhost:8080/actuator/health
```

Stop the application with `Ctrl+C`, then run `make down` to stop PostgreSQL.
Database data remains in its named volume. The first run needs internet access
for dependencies and the database image. See the
[local development guide](docs/development-guide.md) for all commands, environment
variables, IDE setup, migration details, and troubleshooting.

## Testing and verification

```sh
make test
```

Docker is required. Integration tests use an independent PostgreSQL 18
Testcontainers database; `make up` is unnecessary. Unit and MVC tests cover domain
rules, validation, mapping, and the HTTP contract. PostgreSQL tests cover duplicate
suppression, persistence, rollback, concurrent updates, and lock timeout/retry.

The latest recorded full-suite run passed 101 tests;
[S008](docs/specs/008-distance-query.md) records the latest run and distance-query checks. See
[S005 verification evidence](docs/specs/005-travel-distance-processing.md#post-implementation-and-verification-evidence)
for scope and limitations: reloads were tested across transactions, but a full
application-process restart and throughput benchmark have not been performed.
Registration and listing are covered by [S006](docs/specs/006-courier-registration.md).
Docker-free controller test commands are in the
[implementation guide](docs/implementation-guide.md#api-controller-increment).

## Remaining work

- Load the supplied store catalog and implement store-entry detection and logging.
  Accepted entry rules include an inclusive 100-meter boundary and a 60-second
  reentry rule based on the previous physical entry; details are recorded in
  [S001](docs/specs/001-location-and-entry-semantics.md).
- Complete API integration and delivery acceptance checks.
- Add the requested plain HTML page for manually testing location submission and
  distance queries as the final roadmap step.

The [roadmap](docs/roadmap.md) tracks these items and their dependencies.
