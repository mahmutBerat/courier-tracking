# Courier Tracking API

A Java and Spring Boot REST API that receives streaming courier locations,
accumulates each courier's travel distance, and (next increment) detects entries
into Migros store areas. Courier state is kept consistent with PostgreSQL
transactions; every behavior is backed by unit and integration tests.

**Status:** courier registration/listing, location submission, duplicate
suppression, travel-distance accumulation, and distance queries are implemented.
Store catalog loading and store-entry detection are the remaining case-study
scope; see [Remaining work](#remaining-work).

## Key capabilities

- Register couriers explicitly and list registered courier IDs.
- Persist every distinct location report for traceability.
- Suppress exact duplicates with a database unique constraint and native
  `INSERT ... ON CONFLICT DO NOTHING`.
- Accumulate Haversine distance in meters from each courier's last processed location.
- Keep delayed and same-time reports without letting them change travel state.
- Commit input storage and courier state in one transaction, serialized per courier
  with `SELECT FOR UPDATE`.
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
common/
  geo/           GeoLocation value object and distance Strategy (Haversine)
  mapping/       Shared MapStruct configuration
  api/           OpenAPI configuration
```

The controller calls the concrete `CourierService`. The service loads and locks
the courier, stores the input, and invokes `Courier.updateDistance`, which owns the
ordering and accumulation rules. Domain objects never access repositories or HTTP
types. A `store` feature package will be added with store-entry detection.

### Design patterns

| Pattern    | Where                                                                | Purpose                                                                                                                                      |
|------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Strategy   | `DistanceCalculatorStrategy` / `HaversineDistanceCalculatorStrategy` | Keeps the geographic formula replaceable and the `Courier` aggregate independent of it; the same strategy will serve courier–store proximity |
| Repository | `CourierRepository`, `CourierLocationRepository` (Spring Data JPA)   | Encapsulates persistence of current courier state and input history behind interfaces used only by the application service                   |

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
5. Input storage and courier state commit together; a failure rolls back both.
6. Distance queries read persisted courier state only; input history is never replayed.

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

### ADR-009: Store entry semantics (accepted, not yet implemented)

### ADR-010: PostgreSQL 18, Liquibase native SQL, Testcontainers

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

- Domain and geography unit tests: ordering, accumulation, invalid results,
  coordinate validation, Haversine known distances and edge cases.
- Controller and JSON slice tests: binding, validation, status codes, Problem Details.
- PostgreSQL integration tests: duplicate suppression (including eight concurrent
  identical requests), millisecond truncation and migration SQL, rollback with retry,
  same-courier lock waits observed through `pg_stat_activity`, independent courier
  progress, concurrent first reports, registration races, OpenAPI document contents.

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

- Add `stores.json` to the project and load the five supplied stores.
- Implement store-entry detection per ADR-009 within the existing per-courier
  transaction: pair proximity state, counted/uncounted `StoreEntry` persistence,
  and entry logging. Milestone M5 in the [roadmap](docs/roadmap.md).
- Final delivery checks: end-to-end scenario, clean build, documentation review.
