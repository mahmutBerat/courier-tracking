# Courier Tracking API

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
- Validate coordinates and required fields; report errors as RFC 9457 Problem Details.
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
hands it to `StoreEntryDetector`, which walks the store catalog and lets
`StoreProximityState` decide whether a physical entry occurred and whether it
counts; a report enters at most one store. Domain objects never access
repositories or HTTP types.

### Design patterns

| Pattern    | Where                                                                                    | Purpose                                                                                                                                     |
|------------|------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Strategy   | `DistanceCalculatorStrategy` / `HaversineDistanceCalculatorStrategy`                     | Keeps the geographic formula replaceable and the domain independent of it; one strategy serves both travel segments and store proximity     |
| Repository | `CourierRepository`, `CourierLocationRepository`, `StoreProximityStateRepository`, `StoreEntryRepository` (Spring Data JPA) | Encapsulates persistence behind interfaces used only by application services; domain objects never touch them                               |

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

### ADR-002: API-first REST contract with Problem Details

- **Context:** The case supplies the input fields (`time`, `courier`, `lat`, `lng`)
  and a query for total distance.
- **Decision:** Define the HTTP contract first: POST `/api/v1/couriers/locations`
  returns `204`; GET `/api/v1/couriers/distance?courierId=` returns `200` with
  `courierId` and `distanceMeters` or `404`; errors use RFC 9457 Problem Details via
  Spring's `ResponseEntityExceptionHandler`. Courier IDs are strings; `time` binds
  to `Instant`.
- **Alternatives:** Courier ID as a path variable; custom error envelope with codes.
- **Trade-offs:** Standard, tool-friendly error format with no custom envelope to
  maintain; the query-parameter style keeps the courier ID out of the path at the
  cost of a less conventional resource URL. Spec: [S002](docs/specs/002-api-contract-and-controllers.md).

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
| Testing                   | JUnit 5, Mockito, AssertJ, Spring Boot Test, PostgreSQL Testcontainers |
| Build and local execution | Gradle Wrapper, GNU Make, Docker Compose                               |

## Run locally

Requirements: JDK 25, Docker Engine/Desktop with Compose v2, GNU Make.

```sh
make run
```

This starts PostgreSQL, waits for its health check, and runs the application on
`http://localhost:8080`. Then open [Swagger UI](http://localhost:8080/swagger-ui/index.html)
or follow the [curl walkthrough](docs/api-examples.md). Stop with `Ctrl+C` and
`make down`. All commands, environment variables, IDE setup, and troubleshooting
are in the [development guide](docs/development-guide.md).

## Testing

```sh
make test
```

Docker is required; integration tests start their own PostgreSQL 18 container.
Coverage:

- Domain and geography unit tests: travel ordering and accumulation, invalid
  results, coordinate validation, Haversine known distances and edge cases, and
  every store-entry rule (inclusive 100 m boundary, 59/60/61 s re-entries,
  interval measured from uncounted entries, pair independence).
- Catalog tests: the real `stores.json` loads the five stores with exact
  coordinates; empty or invalid definitions fail startup.
- Service tests with Mockito: orchestration, failure propagation, and that only
  reports which advanced travel state reach store evaluation.
- Controller and JSON slice tests: binding, validation, status codes, Problem Details.
- PostgreSQL integration tests: duplicate suppression (including eight concurrent
  identical requests), millisecond truncation and migration SQL, rollback with retry,
  same-courier lock waits observed through `pg_stat_activity`, independent courier
  progress, concurrent first reports, registration races, OpenAPI document contents,
  store entries through HTTP (counted/uncounted chain, stale and duplicate reports,
  rollback of the whole transaction, courier independence, eight concurrent
  reports inside a store yielding exactly one entry).

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

## Remaining work

- Delivery polish (milestone M7): end-to-end walkthrough against a fresh database,
  clean build, final documentation review.
- Optional: a plain HTML test page (M8) and container/CI packaging, subject to
  owner decision.
