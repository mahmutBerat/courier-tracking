# Courier Tracking API

[![CI](https://github.com/mahmutBerat/courier-tracking/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/mahmutBerat/courier-tracking/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F)
![Gradle](https://img.shields.io/badge/Gradle-9.8.1-02303A)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1)

A Java and Spring Boot REST API that receives streaming courier locations,
accumulates each courier's travel distance, and detects entries into Migros store
areas. Courier state is kept consistent with PostgreSQL transactions; every
behavior is backed by unit and integration tests.

**Status:** all case-study behavior is implemented: courier registration/listing,
location submission with duplicate suppression, travel-distance accumulation and
query, and store-entry detection with the one-minute rule. Delivery acceptance (M7)
was closed on 2026-10-26; see [Remaining work](#remaining-work) for what is
intentionally left out and the [documentation index](docs/README.md#key-capabilities)
for the full capability list.

## Quick start

Requirements: JDK 25, Docker Engine/Desktop with Compose v2, GNU Make.

```sh
make run
```

This starts PostgreSQL and a single-node Kafka broker in Compose, waits for their
health checks, and runs the application on `http://localhost:8080`. Then open
[demo.html](http://localhost:8080/demo.html): a plain browser page that runs the
HTTP walkthrough (register a courier, submit locations, query the distance and store
visits) and a Kafka tab with the sample producer commands and their HTTP
verification. Stop with `Ctrl+C` and `make down`.

## Architecture

Feature-first modular monolith under `com.mbi.couriertracking`:

```text
courier/
  api/           Controller, request/response records, response mapper, exception advice
  application/   CourierService: use-case orchestration and transaction boundary; CourierSummary read model
  domain/        Courier aggregate: travel ordering, accumulation rules, store summary
  persistence/   Input history record, Spring Data repositories, persistence mapper
  messaging/     Kafka inbound adapter: message record, mapper, listener, Kafka configuration (created only when `courier.kafka.enabled=true`)
store/
  application/   StoreEntryDetector, catalog loading from stores.json
  domain/        Store, StoreCatalog, StoreVisit (entry rules, open/closed visit), CourierStoreVisits (per-report transitions)
  persistence/   Spring Data repository for visits (latest visit per store query)
common/
  geo/           GeoLocation value object and distance Strategy (Haversine)
  mapping/       Shared MapStruct configuration
  persistence/   BaseEntity: created_at/updated_at base class for every JPA entity
  api/           OpenAPI configuration
```

The request flow through these packages is described step by step in the
[implementation guide](docs/implementation-guide.md#request-processing).

### Design patterns

| Pattern  | Where                                                                                        | Purpose                                                                                                                                                                                             |
|----------|----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Strategy | `DistanceCalculatorStrategy` / `HaversineDistanceCalculatorStrategy`                         | Keeps the geographic formula replaceable and the domain independent of it; one strategy serves both travel segments and store proximity                                                             |
| Adapter  | `CourierLocationKafkaAdapter` with `CourierLocationMessageMapper` (created only when `courier.kafka.enabled=true`) | Adapts Kafka records to the `submitLocation` use case: parse, map, validate, delegate. The service and domain never see the transport; HTTP and Kafka share one validation and one transaction path |

## API overview

| Method | Endpoint                                  | Behavior                                                                                                                                                |
|--------|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| POST   | `/api/v1/couriers`                        | Register an ID: `201`; `409` if it exists; `400` if blank                                                                                               |
| GET    | `/api/v1/couriers`                        | Sorted array of `{"courierId"}`; `[]` when empty                                                                                                        |
| POST   | `/api/v1/couriers/locations`              | Store the report and update travel distance: `204` (also for exact duplicates); `400` invalid fields/coordinates; `404` unknown courier; `415` non-JSON |
| GET    | `/api/v1/couriers/distance?courierId=...` | `200` `{"courierId","distanceMeters","lastStoreName","lastStoreEntryTime","currentStatus","isEntry"}`: distance zero before any location, store fields null before the first entry, `currentStatus` is `in-store` or `out-store`, `isEntry` is true only when the last processed report opened a new visit; `404` unknown courier; `400` missing/blank parameter |
| GET    | `/actuator/health`                        | Application health                                                                                                                                      |

POST location body: `{"time":"2026-10-21T12:00:00Z","courier":"courier-1","lat":40.9923307,"lng":29.1244229}`.
`time` is a UTC instant (explicit offsets are normalized); latitude must be within
[-90, 90] and longitude within [-180, 180]. See [Swagger and curl examples](docs/api-examples.md).
With the Kafka ingress enabled the same JSON arrives as a message on `courier-locations`
(key = courier ID); see [Kafka messages](docs/api-examples.md#kafka-messages).

## Processing rules in brief

1. The courier must exist; unknown IDs get `404` and nothing is stored.
2. A report's identity is `(courier, time truncated to milliseconds, lat, lng)`.
   An exact repeat returns `204` without a new row or state change.
3. The first processed report initializes the courier at zero distance.
4. A report whose time is strictly later than the courier's last processed time
   adds the Haversine distance from the last processed location and becomes the
   new last location/time. Older or same-time distinct reports are stored but do
   not change travel state; the next segment still starts from the last processed location.
5. A report that advanced travel state is compared with every store in catalog
   order. Moving from outside to inside the 100 m area (inclusive) is a physical
   entry and opens a visit in `courier_store_visits`, which is the entry log required
   by the case. If the pair's latest visit began less than 60 s earlier (by input
   time), the re-entry is not an entrance: that visit resumes and no row is added.
   The first report outside the area closes the visit with its `exit_time`; an open
   visit means the courier is inside.
6. The courier row keeps `current_store_name`, `last_store_name`,
   `last_store_entry_time`, and `last_report_entered` (whether the last processed
   report opened a new visit); input storage, courier state, and visits commit
   together, and a failure rolls back all of them.
7. Distance queries read the persisted courier row only (total, last store, last
   entry time, in-store/out-store, entrance flag); input history and visits are
   never replayed.

## Architectural decisions

The five decisions that shape the system are summarized here in ADR form. All
fifteen, including the API contract, duplicate suppression, travel ordering, the
Haversine Strategy, registration, the platform, CI, Kafka ingress, and audit
timestamps, are recorded with the same structure in
[Architectural decisions](docs/architecture-decisions.md). The linked
specifications hold detailed behavior, acceptance criteria, and verification
evidence.

### ADR-001: Modular monolith with feature-first packages

- **Context:** One deployable with two closely related capabilities (courier travel,
  store entries) sharing a transaction boundary.
- **Decision:** A single Spring Boot application organized by feature
  (`courier`, `store`, `common`), each with `api/application/domain/persistence`
  layers created only when needed. Controllers call concrete services; no service
  interfaces exist solely for injection or mocking.
- **Alternatives:** Technical-layer packages; separate services per capability.
- **Trade-offs:** Simple deployment and transactions, readable ownership per feature;
  features cannot scale independently. Spec: [S003](docs/specs/003-application-structure.md).

### ADR-003: Input history separate from current state

- **Context:** Every report should be traceable, but calculations must stay cheap
  and must not depend on replaying history.
- **Decision:** Store each distinct report in `courier_location_inputs` (write-only
  for normal processing). Keep the current calculation state (last location, last
  processed time, total distance) on the `Courier` aggregate in `couriers`.
  Distance queries read `couriers` only, including its store summary (ADR-014),
  without scanning input history or visits.
- **Alternatives:** Recompute totals from history on each query; event sourcing.
- **Trade-offs:** O(1) reads and writes per report and a clear audit trail; a bug in
  accumulation cannot be corrected by re-reading history without an explicit
  replay tool, which is deliberately out of scope.
  Spec: [S004](docs/specs/004-location-submission.md), [S005](docs/specs/005-travel-distance-processing.md).

### ADR-005: Per-courier pessimistic locking in one short transaction

- **Context:** Concurrent reports for the same courier must not lose accumulated
  distance; input storage and state update must be atomic.
- **Decision:** `CourierService.submitLocation` runs in one transaction: load the
  courier row with `SELECT ... FOR UPDATE`, insert the input, apply the domain
  operation, and let JPA flush at commit. Couriers are independent lock scopes.
  Courier creation is a conflict-safe `INSERT ... ON CONFLICT DO NOTHING`.
- **Alternatives:** Optimistic `@Version` with bounded retry; conditional SQL
  `UPDATE ... WHERE last_processed_time < ?`; JVM-local synchronization.
- **Trade-offs:** No retry logic and no lost updates across instances; a busy
  courier serializes its writers and holds a connection while waiting. No
  transaction timeout is configured; contention behavior is left to the database
  and connection pool defaults. Spec: [S005](docs/specs/005-travel-distance-processing.md).

### ADR-009: Store entry semantics

- **Context:** The case requires logging when a courier enters a 100 m radius of a
  store and not counting re-entries to the same store within one minute. The
  wording "reentries ... over 1 minute" is ambiguous.
- **Decision:** Proximity state belongs to each courier–store pair. An entry is an
  outside→inside transition (inclusive boundary: `≤ 100 m` is inside). A first
  observation already inside counts as an entry. A re-entry is a new entrance only
  when `≥ 60 s` have passed since the pair's latest visit began; a re-entry sooner
  is not an entrance, resumes that visit, and is not recorded (owner decision
  2026-10-26, S001-D05; until then such re-entries were stored with `counted=false`
  and moved the reference time). Entry time is the report's input `time`, the same
  clock that orders travel processing; only reports eligible for travel processing
  (strictly newer) are evaluated for proximity. Every persisted visit is one
  entrance (`StoreVisit` since ADR-014).
  The five stores are loaded from `stores.json` into an immutable in-memory catalog.
- **Alternatives:** Server processing time as the entry clock; a single per-courier
  timer; comparing with the last *counted* entry; exclusive boundary; a `stores` table.
- **Trade-offs:** Per-pair state is precise and testable; because the minute is
  measured from the visit's entry, a courier bouncing at the boundary gets a new
  entrance at most once per minute. Input time keeps entry counting
  deterministic and replay-safe but trusts the client clock. An in-memory catalog
  means adding a store requires a redeploy, which matches the supplied-file
  requirement. Spec: [S001](docs/specs/001-location-and-entry-semantics.md),
  [S009](docs/specs/009-store-entry-detection.md).

### ADR-014: Store visits replace pair state and the entry log

- **Context:** The S009 persistence split inside/outside state (`courier_store_proximity`,
  an `inside` boolean) from the entry log (`store_entries`, a `counted` boolean).
  Entry, inside, exit, and outside had to be reconstructed from two tables, exits
  were never recorded, and the demo needed "which store, since when" after every
  update.
- **Decision:** One `courier_store_visits` table: a row per visit with `entry_time`
  and `exit_time` (null while inside). Inside is derived from an open
  visit; a partial unique index allows one open visit per courier–store pair; the
  one-minute rule reads the pair's latest visit. The detector loads the latest
  visit per store with one JPQL query (a correlated `MAX(entryTime)` subquery, so
  Hibernate does not flush the dirty courier before the read). The courier row keeps a summary
  (`current_store_name`, `last_store_name`, `last_store_entry_time`,
  `last_report_entered`) written in the same transaction, and the distance query
  returns it with `in-store`/`out-store` and `isEntry` (true only when the last
  processed report opened a new visit)
  through a `CourierSummary` read model, so entities never reach the controller.
  The two old tables were dropped without migrating their rows (local data only);
  since ADR-015 the changelog creates only the current tables. Entry semantics
  (ADR-009) are unchanged.
- **Alternatives:** Keep both tables and replace the booleans with enums (smallest
  change, exits still missing); an append-only transition event log (complete
  history, but current state needs a last-event query or a projection table);
  migrating the old rows (unknown historical exit times and a Liquibase test harness).
- **Trade-offs:** The visits table is updated on exit, so it is no longer
  append-only. `exit_time` is the input time of the first outside report, not the
  real moment of leaving. The courier summary duplicates what the visits table can
  derive, in exchange for a single-row read on every distance query. When store
  areas overlap, the summary names the most recently entered open store.
  Spec: [S011](docs/specs/011-store-visits-and-courier-summary.md).

### Other decisions

- [ADR-002: API-first REST contract with a uniform error body](docs/architecture-decisions.md#adr-002-api-first-rest-contract-with-a-uniform-error-body)
- [ADR-004: Database-level duplicate suppression](docs/architecture-decisions.md#adr-004-database-level-duplicate-suppression)
- [ADR-006: Travel ordering by input time, strictly newer only](docs/architecture-decisions.md#adr-006-travel-ordering-by-input-time-strictly-newer-only)
- [ADR-007: Haversine distance behind a Strategy](docs/architecture-decisions.md#adr-007-haversine-distance-behind-a-strategy)
- [ADR-008: Explicit courier registration](docs/architecture-decisions.md#adr-008-explicit-courier-registration)
- [ADR-010: The entry log is a database table](docs/architecture-decisions.md#adr-010-the-entry-log-is-a-database-table)
- [ADR-011: PostgreSQL 18, Liquibase native SQL, Testcontainers](docs/architecture-decisions.md#adr-011-postgresql-18-liquibase-native-sql-testcontainers)
- [ADR-012: GitHub Actions CI, JaCoCo coverage, and a Compose-packaged application](docs/architecture-decisions.md#adr-012-github-actions-ci-jacoco-coverage-and-a-compose-packaged-application)
- [ADR-013: Optional Kafka ingress through an inbound Adapter](docs/architecture-decisions.md#adr-013-optional-kafka-ingress-through-an-inbound-adapter)
- [ADR-015: Audit timestamps on every table and a create-only baseline changelog](docs/architecture-decisions.md#adr-015-audit-timestamps-on-every-table-and-a-create-only-baseline-changelog)

## Technology stack

| Area                      | Technology                                                                                   |
|---------------------------|----------------------------------------------------------------------------------------------|
| Runtime                   | Java 25, Spring Boot 4.1.1                                                                   |
| HTTP and validation       | Spring Web MVC, Jakarta Validation                                                           |
| Persistence               | PostgreSQL 18, Spring Data JPA, Hibernate                                                    |
| Migrations                | Liquibase, native SQL changeSets in one master XML                                           |
| Mapping and boilerplate   | MapStruct 1.6.3, Lombok                                                                      |
| API documentation         | springdoc-openapi 3.1.1                                                                      |
| Health                    | Spring Boot Actuator                                                                         |
| Messaging                 | Spring for Apache Kafka 4.1.1, flag-enabled ingress, Apache Kafka 4.3 broker in Compose       |
| Testing                   | JUnit 5, Mockito, AssertJ, Awaitility, Spring Boot Test, PostgreSQL and Kafka Testcontainers |
| Coverage and CI           | JaCoCo, GitHub Actions                                                                       |
| Build and local execution | Gradle Wrapper, GNU Make, Docker Compose, multi-stage Dockerfile                             |

## Run locally

`make run` ([Quick start](#quick-start)) is the default way to run the application:
PostgreSQL, the Kafka broker, and the application with the `local` profile, which
enables the Kafka ingress (ADR-013). Besides [demo.html](http://localhost:8080/demo.html),
use [Swagger UI](http://localhost:8080/swagger-ui/index.html) or the
[curl walkthrough](docs/api-examples.md). All commands, environment variables, IDE
setup, and troubleshooting are in the [development guide](docs/development-guide.md).

To produce the sample Kafka messages from a second terminal while the application runs:

```sh
make kafka-send         # register kafka-courier-1 and produce the sample location messages
make kafka-send-invalid # three unprocessable messages: skipped and logged at INFO
```

The message contract is in the [development guide](docs/development-guide.md#kafka-ingress)
and [API examples](docs/api-examples.md#kafka-messages).

To run the whole stack in containers without a local JDK:

```sh
make stack-up
```

This builds the application image from the Dockerfile and starts PostgreSQL, Kafka,
and the application on `http://localhost:8080`. Follow logs with `make stack-logs`
and stop with `make down`.

## Testing

```sh
make test
```

Docker is required; integration tests start their own PostgreSQL 18 container.
The `test` task finalizes with a JaCoCo report at
`build/reports/jacoco/test/html/index.html`.
Coverage:

- Domain and geography unit tests: travel ordering and accumulation, invalid
  results, coordinate validation, Haversine known distances and edge cases, and
  every store-entry rule (inclusive 100 m boundary, 59/60/61 s re-entries, the
  interval measured from the visit's entry rather than its exit, store switches in
  one report, pair independence).
- Catalog tests: the real `stores.json` loads the five stores with exact
  coordinates; empty or invalid definitions fail startup.
- Service tests with Mockito: orchestration, failure propagation, and that only
  reports which advanced travel state reach store evaluation.
- Controller and JSON slice tests: binding, validation, status codes, the uniform
  `ErrorResponse` body with codes and field errors.
- PostgreSQL integration tests: duplicate suppression (including eight concurrent
  identical requests), millisecond truncation and migration SQL, rollback with retry,
  same-courier lock waits observed through `pg_stat_activity`, independent courier
  progress, concurrent first reports, registration races, OpenAPI document contents,
  store visits through HTTP (open, close, and resume within one minute, store
  switch, stale and duplicate reports, rollback of the whole transaction, courier
  independence, eight concurrent reports inside a store yielding exactly one
  visit, one open visit per pair enforced by the database).

## Continuous integration

The [GitHub Actions workflow](https://github.com/mahmutBerat/courier-tracking/actions/workflows/ci.yml)
runs `./gradlew clean build` for pushes to `main`, pull requests targeting `main`,
and manual dispatches. Integration tests run against PostgreSQL Testcontainers on
the runner. Each run publishes the test and JaCoCo reports as artifacts, writes a
quality summary to the run page, and keeps one updated summary comment on pull
requests.

## Documentation

| Document                                             | Purpose                                                       |
|------------------------------------------------------|---------------------------------------------------------------|
| [Documentation index](docs/README.md)                | Working cycle, milestone review, specification conventions    |
| [Architectural decisions](docs/architecture-decisions.md) | All fifteen ADRs, including superseded ones              |
| [Specifications](docs/specs)                         | Behavior, acceptance criteria, and verification per increment |
| [API examples](docs/api-examples.md)                 | Swagger and ordered curl requests with expected responses     |
| [Development guide](docs/development-guide.md)       | Commands, configuration, migrations, troubleshooting          |
| [Implementation guide](docs/implementation-guide.md) | Conventions for Lombok, MapStruct, packages, geography        |
| [Local skills](docs/skills)                          | Spring Boot engineering and unit-test guidance for agents     |

## Remaining work

- Kafka follow-up: publish skipped messages to a dead-letter topic (S010-D14).
- Not planned for this delivery: SpotBugs static analysis, trace correlation
  headers, OTLP export, a Postman collection, and Avro or a Schema Registry for
  the Kafka payload.
