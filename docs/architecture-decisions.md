# Architectural decisions

Every accepted architectural decision in ADR form (Context, Decision, Alternatives,
Trade-offs), including decisions later superseded. The root README repeats the five
that shape the system most; the linked specifications hold detailed behavior,
acceptance criteria, and verification evidence.

| ADR | Title | Status |
|---|---|---|
| ADR-001 | [Modular monolith with feature-first packages](#adr-001-modular-monolith-with-feature-first-packages) | Accepted |
| ADR-002 | [API-first REST contract with a uniform error body](#adr-002-api-first-rest-contract-with-a-uniform-error-body) | Amended 2026-10-23 (uniform `ErrorResponse`) |
| ADR-003 | [Input history separate from current state](#adr-003-input-history-separate-from-current-state) | Accepted |
| ADR-004 | [Database-level duplicate suppression](#adr-004-database-level-duplicate-suppression) | Accepted |
| ADR-005 | [Per-courier pessimistic locking in one short transaction](#adr-005-per-courier-pessimistic-locking-in-one-short-transaction) | Accepted |
| ADR-006 | [Travel ordering by input time, strictly newer only](#adr-006-travel-ordering-by-input-time-strictly-newer-only) | Accepted |
| ADR-007 | [Haversine distance behind a Strategy](#adr-007-haversine-distance-behind-a-strategy) | Accepted |
| ADR-008 | [Explicit courier registration](#adr-008-explicit-courier-registration) | Accepted |
| ADR-009 | [Store entry semantics](#adr-009-store-entry-semantics) | Accepted |
| ADR-010 | [The entry log is a database table](#adr-010-the-entry-log-is-a-database-table) | Amended by ADR-014 (the log table is `courier_store_visits`) |
| ADR-011 | [PostgreSQL 18, Liquibase native SQL, Testcontainers](#adr-011-postgresql-18-liquibase-native-sql-testcontainers) | Accepted |
| ADR-012 | [GitHub Actions CI, JaCoCo coverage, and a Compose-packaged application](#adr-012-github-actions-ci-jacoco-coverage-and-a-compose-packaged-application) | Accepted |
| ADR-013 | [Optional Kafka ingress through an inbound Adapter](#adr-013-optional-kafka-ingress-through-an-inbound-adapter) | Accepted |
| ADR-014 | [Store visits replace pair state and the entry log](#adr-014-store-visits-replace-pair-state-and-the-entry-log) | Accepted |
| ADR-015 | [Audit timestamps on every table and a create-only baseline changelog](#adr-015-audit-timestamps-on-every-table-and-a-create-only-baseline-changelog) | Accepted |

### ADR-001: Modular monolith with feature-first packages

- **Context:** One deployable with two closely related capabilities (courier travel,
  store entries) sharing a transaction boundary.
- **Decision:** A single Spring Boot application organized by feature
  (`courier`, `store`, `common`), each with `api/application/domain/persistence`
  layers created only when needed. Controllers call concrete services; no service
  interfaces exist solely for injection or mocking.
- **Alternatives:** Technical-layer packages; separate services per capability.
- **Trade-offs:** Simple deployment and transactions, readable ownership per feature;
  features cannot scale independently. Spec: [S003](specs/003-application-structure.md).

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
  URL. Spec: [S002](specs/002-api-contract-and-controllers.md).

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
  Spec: [S004](specs/004-location-submission.md), [S005](specs/005-travel-distance-processing.md).

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
  GPS data). Millisecond precision discards finer fractions. Spec: [S004](specs/004-location-submission.md).

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
  and connection pool defaults. Spec: [S005](specs/005-travel-distance-processing.md).

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
  Spec: [S005](specs/005-travel-distance-processing.md).

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
  Spec: [S005](specs/005-travel-distance-processing.md).

### ADR-008: Explicit courier registration

- **Context:** Early increments created a courier row implicitly on the first
  report, which made unknown IDs indistinguishable from typos.
- **Decision:** Couriers are registered with POST `/api/v1/couriers` and listed
  with GET. Location submission for an unregistered ID returns `404` without
  storing anything. Registration never resets existing travel state.
- **Alternatives:** Implicit creation on first report.
- **Trade-offs:** One extra call before streaming, but a clear lifecycle, a
  meaningful `404`, and no accidental couriers.
  Spec: [S006](specs/006-courier-registration.md).

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
  requirement. Spec: [S001](specs/001-location-and-entry-semantics.md),
  [S009](specs/009-store-entry-detection.md).

### ADR-010: The entry log is a database table

- **Context:** The case asks to "log courier and store" when a courier enters a
  store area.
- **Decision:** Every entrance is persisted with courier ID, store name, and entry
  time, in the same transaction as the report (uncounted re-entries were stored with
  a `counted` flag until S001-D05 stopped recording them). No
  application log line or event is emitted for entries. The table was
  `store_entries`; since ADR-014 it is `courier_store_visits`, which also carries the exit.
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
  wants a realistic streaming entry point next to the REST endpoint, the Adapter
  pattern alongside Strategy, and hands-on Kafka experience, while the
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
  created only when `courier.kafka.enabled=true`, which the `local` Spring profile
  sets (the separate `kafka` Spring profile was merged into `local` on 2026-10-26); Compose starts a single-node KRaft broker with every `make run` and `make stack-up`
  (the Compose `kafka` profile was dropped on 2026-10-26, S010-D15) and Make
  targets produce sample messages with the console producer. Spec:
  [S010](specs/010-kafka-location-ingress.md).
- **Alternatives:** Kafka as the only input with REST removed; a service interface
  ("port") with HTTP and Kafka implementations; Avro with a Schema Registry; Spring
  Cloud Stream; Kafka transactions for exactly-once; a dead-letter topic now instead
  of later.
- **Trade-offs:** Two inputs must stay consistent, mitigated by one validation and
  one service path. The Kafka integration test starts a second Testcontainer and
  lengthens the suite. At-least-once means duplicates reach the database and are
  rejected there. With the ingress enabled and the broker unreachable, startup is
  delayed by the admin client's topic-creation attempts (about 40 s observed) but
  succeeds, and the consumer joins when the broker appears.

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
  Spec: [S011](specs/011-store-visits-and-courier-summary.md).

### ADR-015: Audit timestamps on every table and a create-only baseline changelog

- **Context:** Rows carried no record of when they were inserted or last written,
  which hampers tracing during the demo and in incident analysis. The changelog had
  grown to six changeSets, two of which created and then dropped tables that no code
  uses, and three of which reshaped `courier_location_inputs` for data that only
  ever existed locally.
- **Decision:** Every JPA entity extends `BaseEntity` (`common.persistence`), a
  `@MappedSuperclass` with `created_at` and `updated_at` set by `@PrePersist` and
  `@PreUpdate` at millisecond precision; the columns also carry `DEFAULT now()` so
  the native `INSERT ... ON CONFLICT` statements for couriers and inputs are stamped
  by the database. The changelog is rebased to one `CREATE TABLE` changeSet per
  current table (`couriers`, `courier_location_inputs`, `courier_store_visits`) in its final
  shape; the historical ALTER and DROP changeSets are removed. Existing local
  databases are recreated (`docker compose down -v`).
- **Alternatives:** Spring Data JPA auditing (`@EnableJpaAuditing`, `@CreatedDate`),
  which adds configuration for the same two columns; database triggers, which hide
  the rule from the code; keeping the incremental changeSets, which preserves
  history that no deployment needs.
- **Trade-offs:** The timestamps are wall-clock infrastructure data, distinct from
  the input time that drives every business rule. A database migrated with the old
  changeSet ids cannot be upgraded in place; the project has no such database
  outside local volumes. Future schema changes return to incremental changeSets.
