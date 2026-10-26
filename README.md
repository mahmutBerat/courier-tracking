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
query, and store-entry detection with the one-minute rule. See
[Remaining work](#remaining-work) for delivery polish.

## Key capabilities

- Register couriers explicitly and list registered courier IDs.
- Persist every distinct location report for traceability.
- Suppress exact duplicates with a database unique constraint and native
  `INSERT ... ON CONFLICT DO NOTHING`.
- Accumulate Haversine distance in meters from each courier's last processed location.
- Keep delayed and same-time reports without letting them change travel state.
- Commit input storage and courier state in one transaction, serialized per courier
  with `SELECT FOR UPDATE`.
- Detect when a courier enters the 100 m area of any of the five supplied stores,
  apply the one-minute re-entry rule per courier–store pair, and record every entry
  with its counted flag in `store_entries`.
- Validate coordinates and required fields; report every error with one uniform
  `ErrorResponse` body (status, error, code, message, path, optional field errors).
- Consume the same location reports from the Kafka topic `courier-locations` when the
  optional `kafka` profile is active: at-least-once delivery, per-courier ordering by
  key, retries for transient failures, and unprocessable messages skipped with one
  INFO log line (a dead-letter topic is planned).
- Manage schema with Liquibase; verify persistence against real PostgreSQL with Testcontainers.
- Swagger UI, OpenAPI JSON/YAML, and an ordered curl walkthrough.

## Architecture

Feature-first modular monolith under `com.mbi.couriertracking`:

```text
courier/
  api/           Controller, request/response records, response mapper, exception advice
  application/   CourierService: use-case orchestration and transaction boundary
  domain/        Courier aggregate: travel ordering and accumulation rules
  persistence/   Input history record, Spring Data repositories, persistence mapper
  messaging/     Kafka inbound adapter: message record, mapper, listener, Kafka configuration (kafka profile)
store/
  application/   StoreEntryDetector, catalog loading from stores.json
  domain/        Store, StoreCatalog, StoreProximityState (entry rules), StoreEntry
  persistence/   Spring Data repositories for pair state and entries
common/
  geo/           GeoLocation value object and distance Strategy (Haversine)
  mapping/       Shared MapStruct configuration
  api/           OpenAPI configuration
```

The controller calls the concrete `CourierService`. The service loads and locks
the courier, stores the input, and invokes `Courier.updateDistance`, which owns the
ordering and accumulation rules. When the report advanced travel state, the service
hands it to `StoreEntryDetector`, which walks the whole store catalog and lets
each `StoreProximityState` decide whether a physical entry occurred and whether
it counts, so leaving one store while entering another is recorded as well.
Domain objects never access repositories or HTTP types.

### Design patterns

| Pattern    | Where                                                                                    | Purpose                                                                                                                                     |
|------------|------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Strategy   | `DistanceCalculatorStrategy` / `HaversineDistanceCalculatorStrategy`                     | Keeps the geographic formula replaceable and the domain independent of it; one strategy serves both travel segments and store proximity     |
| Repository | `CourierRepository`, `CourierLocationRepository`, `StoreProximityStateRepository`, `StoreEntryRepository` (Spring Data JPA) | Encapsulates persistence behind interfaces used only by application services; domain objects never touch them                               |
| Adapter    | `courier.messaging.CourierLocationKafkaListener` with `CourierLocationMessageMapper` (optional `kafka` profile) | Adapts Kafka records to the `submitLocation` use case: parse, map, validate, delegate. The service and domain never see the transport; HTTP and Kafka share one validation and one transaction path |

## API overview

| Method | Endpoint                                  | Behavior                                                                                                                                                |
|--------|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| POST   | `/api/v1/couriers`                        | Register an ID: `201`; `409` if it exists; `400` if blank                                                                                               |
| GET    | `/api/v1/couriers`                        | Sorted array of `{"courierId"}`; `[]` when empty                                                                                                        |
| POST   | `/api/v1/couriers/locations`              | Store the report and update travel distance: `204` (also for exact duplicates); `400` invalid fields/coordinates; `404` unknown courier; `415` non-JSON |
| GET    | `/api/v1/couriers/distance?courierId=...` | `200` `{"courierId","distanceMeters"}` (zero before any location); `404` unknown courier; `400` missing/blank parameter                                 |
| GET    | `/actuator/health`                        | Application health                                                                                                                                      |

POST location body: `{"time":"2026-10-21T12:00:00Z","courier":"courier-1","lat":40.9923307,"lng":29.1244229}`.
`time` is a UTC instant (explicit offsets are normalized); latitude must be within
[-90, 90] and longitude within [-180, 180]. See [Swagger and curl examples](docs/api-examples.md).
With the `kafka` profile the same JSON arrives as a message on `courier-locations`
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
5. A report that advanced travel state is compared with the stores in catalog
   order until one is entered. Moving from outside to inside the 100 m area
   (inclusive) is a physical entry. It is counted
   unless the same courier–store pair had a physical entry less than 60 s earlier
   (by input time); either way the entry is recorded in `store_entries` with its
   `counted` flag, which is the entry log required by the case.
6. Input storage, courier state, pair state, and entries commit together; a
   failure rolls back all of them.
7. Distance queries read persisted courier state only; input history is never replayed.

## Architectural decisions

Decisions are summarized here in ADR form. The linked specifications hold the
detailed behavior, acceptance criteria, and verification evidence.

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

### ADR-002: API-first REST contract with a uniform error body

- **Context:** The case supplies the input fields (`time`, `courier`, `lat`, `lng`)
  and a query for total distance.
- **Decision:** Define the HTTP contract first: POST `/api/v1/couriers/locations`
  returns `204`; GET `/api/v1/couriers/distance?courierId=` returns `200` with
  `courierId` and `distanceMeters` or `404`. Courier IDs are strings; `time` binds
  to `Instant`. Errors were first delivered as RFC 9457 Problem Details; amended on
  2026-10-23 so that every error, including framework-generated `400` and `415`
  responses, returns the project's `ErrorResponse` record (`status`, `error`,
  `code`, `message`, `path`, optional `fieldErrors`) through `ApiExceptionHandler`,
  which still extends `ResponseEntityExceptionHandler` and translates its bodies.
  Application outcomes are domain exceptions (`CourierNotFoundException`,
  `CourierAlreadyExistsException`) mapped to `404` and `409` in the handler.
- **Alternatives:** Courier ID as a path variable; keeping Problem Details
  (the original choice); a custom body only for application errors with framework
  errors left as Problem Details.
- **Trade-offs:** The custom body carries a stable `code` and field-level
  violations that clients and the planned Kafka dead-letter handling can rely on,
  at the cost of maintaining an envelope instead of the standard media type. One
  shape for all errors avoids two formats on one API. The query-parameter style
  keeps the courier ID out of the path at the cost of a less conventional resource
  URL. Spec: [S002](docs/specs/002-api-contract-and-controllers.md).

### ADR-003: Input history separate from current state

- **Context:** Every report should be traceable, but calculations must stay cheap
  and must not depend on replaying history.
- **Decision:** Store each distinct report in `courier_location_inputs` (write-only
  for normal processing). Keep the current calculation state (last location, last
  processed time, total distance) on the `Courier` aggregate in `couriers`.
  Queries read `couriers` only.
- **Alternatives:** Recompute totals from history on each query; event sourcing.
- **Trade-offs:** O(1) reads and writes per report and a clear audit trail; a bug in
  accumulation cannot be corrected by re-reading history without an explicit
  replay tool, which is deliberately out of scope.
  Spec: [S004](docs/specs/004-location-submission.md), [S005](docs/specs/005-travel-distance-processing.md).

### ADR-004: Database-level duplicate suppression

- **Context:** Streaming clients retry; the same report may arrive more than once,
  including concurrently.
- **Decision:** Report identity is `(courier, time truncated to milliseconds, lat, lng)`,
  enforced by a unique constraint and a native `INSERT ... ON CONFLICT DO NOTHING`.
  Duplicates return `204` and skip all calculation. No idempotency header, no cache.
- **Alternatives:** Client-supplied `Idempotency-Key`; in-memory recent-report cache;
  preliminary `SELECT` before insert.
- **Trade-offs:** Correct across concurrent requests, restarts, and multiple
  instances with one statement; identity is content-based, so a genuinely new
  report with identical content is indistinguishable from a retry (accepted for
  GPS data). Millisecond precision discards finer fractions. Spec: [S004](docs/specs/004-location-submission.md).

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

### ADR-006: Travel ordering by input time, strictly newer only

- **Context:** Reports can arrive delayed or out of order; processing time is not
  a reliable movement clock.
- **Decision:** Compare each report's `time` (millisecond precision) with the
  courier's last processed time. Only a strictly newer report adds a segment and
  advances state; older or same-time distinct reports are stored but ignored for
  distance. No re-sorting or recalculation of history.
- **Alternatives:** Process in arrival order; buffer and sort by time; recompute
  when a late report arrives.
- **Trade-offs:** Deterministic and cheap; a late report that was genuinely part of
  the route is not counted, so totals are a lower bound under heavy reordering.
  Spec: [S005](docs/specs/005-travel-distance-processing.md).

### ADR-007: Haversine distance behind a Strategy

- **Context:** Both travel segments and store proximity need a distance in meters
  between two coordinates.
- **Decision:** `GeoLocation` is an immutable value object validating finite,
  inclusive ranges. `DistanceCalculatorStrategy` is the abstraction;
  `HaversineDistanceCalculatorStrategy` uses a spherical Earth with
  R = 6,371,000 m, `double` precision, no display rounding, and clamps the
  intermediate term against floating-point drift.
- **Alternatives:** Vincenty/geodesic formulas; road-network routing; `BigDecimal`.
- **Trade-offs:** Sub-meter accuracy at this scale with trivial cost; the result is
  the sum of straight segments between samples, not the road distance traveled.
  Spec: [S005](docs/specs/005-travel-distance-processing.md).

### ADR-008: Explicit courier registration

- **Context:** Early increments created a courier row implicitly on the first
  report, which made unknown IDs indistinguishable from typos.
- **Decision:** Couriers are registered with POST `/api/v1/couriers` and listed
  with GET. Location submission for an unregistered ID returns `404` without
  storing anything. Registration never resets existing travel state.
- **Alternatives:** Implicit creation on first report.
- **Trade-offs:** One extra call before streaming, but a clear lifecycle, a
  meaningful `404`, and no accidental couriers.
  Spec: [S006](docs/specs/006-courier-registration.md).

### ADR-009: Store entry semantics

- **Context:** The case requires logging when a courier enters a 100 m radius of a
  store and not counting re-entries to the same store within one minute. The
  wording "reentries ... over 1 minute" is ambiguous.
- **Decision:** Proximity state belongs to each courier–store pair. An entry is an
  outside→inside transition (inclusive boundary: `≤ 100 m` is inside). A first
  observation already inside counts as an entry. A re-entry is counted only when
  `≥ 60 s` have passed since the pair's previous *physical* entry; an uncounted
  entry still updates that timestamp. Entry time is the report's input `time`,
  the same clock that orders travel processing; only reports eligible for travel
  processing (strictly newer) are evaluated for proximity. Counted and uncounted
  entries share one `StoreEntry` model with a `counted` flag and are persisted.
  The five stores are loaded from `stores.json` into an immutable in-memory catalog.
- **Alternatives:** Server processing time as the entry clock; a single per-courier
  timer; comparing with the last *counted* entry; exclusive boundary; a `stores` table.
- **Trade-offs:** Per-pair state is precise and testable; repeated short re-entries
  can suppress counting indefinitely (by design). Input time keeps entry counting
  deterministic and replay-safe but trusts the client clock. An in-memory catalog
  means adding a store requires a redeploy, which matches the supplied-file
  requirement. Spec: [S001](docs/specs/001-location-and-entry-semantics.md),
  [S009](docs/specs/009-store-entry-detection.md).

### ADR-010: The entry log is the `store_entries` table

- **Context:** The case asks to "log courier and store" when a courier enters a
  store area.
- **Decision:** Every physical entry (counted or not) is persisted in
  `store_entries` with courier ID, store name, entry time, and `counted`, in the
  same transaction as the report. No application log line or event is emitted
  for entries.
- **Alternatives:** An INFO log line after commit via an application event
  (implemented and then removed as needless complexity); an outbox and message broker.
- **Trade-offs:** The record is durable, queryable, and consistent with the rest
  of the transaction; operators look in the database rather than in log output.

### ADR-011: PostgreSQL 18, Liquibase native SQL, Testcontainers

- **Context:** Correctness depends on database semantics (`ON CONFLICT`,
  `FOR UPDATE`, timestamp precision), so tests must run against the real engine.
- **Decision:** PostgreSQL 18 via Docker Compose for local runs; schema managed by
  Liquibase with native SQL changeSets in one master changelog and
  `ddl-auto=none`; integration tests use a PostgreSQL Testcontainer; `make` is
  the single command entry point.
- **Alternatives:** H2 for local/tests; Hibernate schema generation; Flyway.
- **Trade-offs:** Tests prove the actual production semantics; Docker and JDK 25
  are required to run and test the project.

### ADR-012: GitHub Actions CI, JaCoCo coverage, and a Compose-packaged application

- **Context:** The reviewer should be able to see the suite pass and run the
  whole stack without a local JDK. The reference project established CI, coverage,
  and container packaging as practices worth carrying over.
- **Decision:** A GitHub Actions workflow runs `./gradlew clean build` on pushes
  to `main`, pull requests, and manual dispatch, publishes test and JaCoCo reports
  as artifacts, and posts a quality summary. JaCoCo finalizes the `test` task. A
  multi-stage Dockerfile builds a non-root runtime image, and Compose runs it as an
  `app` service next to PostgreSQL through `make stack-up`.
- **Alternatives:** No CI; SpotBugs static analysis as in the reference (a
  non-blocking report there); a standalone image without a Compose service; a
  Postman collection alongside Swagger.
- **Trade-offs:** CI exercises the real PostgreSQL Testcontainers suite on the
  runner's Docker daemon, so runs take minutes rather than seconds. SpotBugs,
  tracing headers, and a Postman collection are left out to keep the delivery
  focused; coverage is reported, not enforced as a threshold.

### ADR-013: Optional Kafka ingress through an inbound Adapter

- **Context:** The case describes the input as streaming geolocations. The owner
  wants a realistic streaming entry point next to the REST endpoint, a stronger
  second design pattern than Repository, and hands-on Kafka experience, while the
  reviewer must still be able to run the application without a broker.
- **Decision:** A `courier.messaging` package holds an inbound Adapter: a
  `CourierLocationMessage` record, a MapStruct mapper to `CourierLocationInput`, and
  a `@KafkaListener` that parses the JSON value with the application's Jackson
  mapper, validates the mapped input with the shared constraints, and calls the
  concrete `CourierService.submitLocation`. Messages that can never succeed
  (unparseable JSON, empty payload, constraint violations, unknown courier) are
  skipped at once; other failures get three attempts in total (two retries, one
  second apart), then are skipped. A skipped record is logged once at INFO with topic, partition, offset,
  key, and exception class; publishing it to a dead-letter topic was implemented
  and then removed by the owner on 2026-10-23 to keep this increment small
  (S010-D14), and remains the planned next step. Delivery is at-least-once; redelivered records
  are absorbed by the duplicate suppression of ADR-004. Everything Kafka-specific is
  created only when `courier.kafka.enabled=true`, which the `kafka` Spring profile
  sets; Compose starts a single-node KRaft broker under the `kafka` profile and Make
  targets produce sample messages with the console producer. Spec:
  [S010](docs/specs/010-kafka-location-ingress.md).
- **Alternatives:** Kafka as the only input with REST removed; a service interface
  ("port") with HTTP and Kafka implementations; Avro with a Schema Registry; Spring
  Cloud Stream; Kafka transactions for exactly-once; a dead-letter topic now instead
  of later.
- **Trade-offs:** Two inputs must stay consistent, mitigated by one validation and
  one service path. The Kafka integration test starts a second Testcontainer and
  lengthens the suite. At-least-once means duplicates reach the database and are
  rejected there. With the profile active and the broker unreachable, startup is
  delayed by the admin client's topic-creation attempts (about 40 s observed) but
  succeeds, and the consumer joins when the broker appears.

## Technology stack

| Area                      | Technology                                                             |
|---------------------------|------------------------------------------------------------------------|
| Runtime                   | Java 25, Spring Boot 4.1.1                                             |
| HTTP and validation       | Spring Web MVC, Jakarta Validation                                     |
| Persistence               | PostgreSQL 18, Spring Data JPA, Hibernate                              |
| Migrations                | Liquibase, native SQL changeSets in one master XML                     |
| Mapping and boilerplate   | MapStruct 1.6.3, Lombok                                                |
| API documentation         | springdoc-openapi 3.1.1                                                |
| Health                    | Spring Boot Actuator                                                   |
| Messaging                 | Spring for Apache Kafka 4.1.1, optional `kafka` profile, Apache Kafka 4.3 broker in Compose |
| Testing                   | JUnit 5, Mockito, AssertJ, Awaitility, Spring Boot Test, PostgreSQL and Kafka Testcontainers |
| Coverage and CI           | JaCoCo, GitHub Actions                                                 |
| Build and local execution | Gradle Wrapper, GNU Make, Docker Compose, multi-stage Dockerfile       |

## Run locally

Requirements: JDK 25, Docker Engine/Desktop with Compose v2, GNU Make.

```sh
make run
```

This starts PostgreSQL, waits for its health check, and runs the application on
`http://localhost:8080`. Then open [Swagger UI](http://localhost:8080/swagger-ui/index.html)
or follow the [curl walkthrough](docs/api-examples.md). For manual testing only,
[demo.html](http://localhost:8080/demo.html) runs the success-path walkthrough from the browser, with a second tab for the Kafka sample commands and their HTTP verification. Stop with `Ctrl+C` and
`make down`. All commands, environment variables, IDE setup, and troubleshooting
are in the [development guide](docs/development-guide.md).

To run the whole stack in containers without a local JDK:

```sh
make stack-up
```

This builds the application image from the Dockerfile and starts PostgreSQL and
the application on `http://localhost:8080`. Follow logs with `make stack-logs`
and stop with `make down`.

With Kafka (optional, see ADR-013):

```sh
make run-kafka          # PostgreSQL + single-node Kafka broker + application with the local,kafka profiles
make kafka-send         # register kafka-courier-1 and produce the sample location messages
make kafka-send-invalid # three unprocessable messages: skipped and logged at INFO
```

`make stack-up-kafka` runs the same with the application in a container. Details and
the message contract are in the [development guide](docs/development-guide.md#kafka-profile)
and [API examples](docs/api-examples.md#kafka-messages).

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
  every store-entry rule (inclusive 100 m boundary, 59/60/61 s re-entries,
  interval measured from uncounted entries, pair independence).
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
  store entries through HTTP (counted/uncounted chain, stale and duplicate reports,
  rollback of the whole transaction, courier independence, eight concurrent
  reports inside a store yielding exactly one entry).

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
| [Documentation index](docs/README.md)                | Working cycle, specification conventions, document map        |
| [Initial decisions](docs/initial-decisions.md)       | Case requirements, interpretation, shared domain language     |
| [Roadmap](docs/roadmap.md)                           | Milestones, progress, remaining work                          |
| [Specifications](docs/specs)                         | Behavior, acceptance criteria, and verification per increment |
| [API examples](docs/api-examples.md)                 | Swagger and ordered curl requests with expected responses     |
| [Development guide](docs/development-guide.md)       | Commands, configuration, migrations, troubleshooting          |
| [Implementation guide](docs/implementation-guide.md) | Conventions for Lombok, MapStruct, packages, geography        |
| [Plans](docs/plans)                                  | Implementation plans executed against the specifications      |

## Remaining work

- Delivery polish (milestone M7): end-to-end walkthrough against a fresh database,
  clean build, final documentation review.
- Delivered beyond the case: the HTML test page (M8, `static/demo.html`) and the
  optional Kafka ingress (M9, ADR-013).
- Kafka follow-up: publish skipped messages to a dead-letter topic (S010-D14).
- Not planned for this delivery: SpotBugs static analysis, trace correlation
  headers, OTLP export, a Postman collection, and Avro or a Schema Registry for
  the Kafka payload.
