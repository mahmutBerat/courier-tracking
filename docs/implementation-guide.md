# Implementation guide

Conventions and details behind the [project README](../README.md). For setup and
commands see the [development guide](development-guide.md); for requests see
[Swagger and curl examples](api-examples.md). All code, comments, and
documentation are in English; proper names from the supplied store data keep
their original spelling.

## Request processing

`POST /api/v1/couriers/locations` runs in one transaction:

1. `CourierRepository.findForUpdate(courierId)` — `404` when the courier is not registered.
2. `CourierLocationMapper.toRecord(input)` → `CourierLocationRepository.insertIfAbsent`.
   Zero affected rows means an exact duplicate: return `204` without further work.
3. `courier.updateDistance(location, time, distanceCalculator)` applies the ordering
   and accumulation rules and returns whether the report advanced travel state;
   JPA flushes the aggregate at commit.
4. For an advancing report, `StoreEntryDetector.detectEntries(courierId, location, time)`
   loads the courier's latest visit per store with one query into
   `CourierStoreVisits` and checks every store in the catalog against it. That
   domain object opens a `StoreVisit` on each physical entry (the visit decides
   whether it counts) and closes the open visit of every store the courier left, so
   leaving one store while entering another is recorded as well; the detector saves
   what changed. The detector runs with `Propagation.MANDATORY`: it can only execute
   inside the courier transaction.
5. The `StoreVisitResult` outcome names the visit opened and the store the courier
   is now inside; the service writes that summary on the locked `Courier`
   (`recordStoreEntry`, `updateCurrentStore`).

A database failure rolls back the input row, the courier update, and the visits
together. Unknown couriers are rejected before any write. Duplicates also acquire
the courier lock. Queries receive `CourierSummary` records; entities never reach the
controller. Domain objects never access repositories or HTTP types.

`GET /api/v1/couriers/distance` reads the courier row with `findById` in a
read-only transaction and maps its summary columns to `lastStoreName`,
`lastStoreEntryTime`, `currentStatus`, and `isEntry`. It never scans input history,
reads visits, or locks the row.

Run the Docker-free controller and JSON tests with:

```sh
./gradlew test --tests '*CourierControllerTests' --tests '*CourierLocationInputJsonTests' --tests '*CourierDistanceQueryTests'
```

## Lombok

Compile-only dependency and annotation processor for main and test code, using the
Spring Boot managed version. Controllers and services use `@RequiredArgsConstructor`
with `private final` dependencies. Entities use `@Getter` and a protected no-args
constructor; no `@Data` or blanket setters. See the
[official Gradle setup](https://projectlombok.org/setup/gradle).

## MapStruct

MapStruct 1.6.3 with `lombok-mapstruct-binding` 0.2.0. Every mapper uses
`@Mapper(config = BaseMapperConfig.class)`: Spring component model, constructor
injection, and `unmappedTargetPolicy = ERROR`. Renamed and ignored targets are
declared explicitly; mappers are injected, never instantiated statically.

- `CourierMapper` (`courier.api`): `CourierSummary` → `CourierResponse` and
  `TotalDistanceResponse` (`currentStatus` from the store status value, `isEntry`
  from `lastReportEntered`).
- `CourierSummaryMapper` (`courier.application`): `Courier` → `CourierSummary`
  read model, so entities never reach the controller.
- `CourierLocationMapper` (`courier.persistence`): `CourierLocationInput` →
  `CourierLocationRecord` (renames `courier/lat/lng`, ignores `id`);
  `CourierLocationRecord` → `GeoLocation`.
- `CourierLocationMessageMapper` (`courier.messaging`): Kafka message →
  `CourierLocationInput`.

Reload the Gradle project in IntelliJ after annotation-processor changes. See the
[MapStruct Lombok guide](https://mapstruct.org/documentation/stable/reference/html/#lombok).

## Packages

| Package | Contents |
|---|---|
| `courier.api` | `CourierController`, request/response records, `CourierMapper`, `ApiExceptionHandler` |
| `courier.application` | `CourierService`, `CourierSummary` read model and its mapper, domain exceptions |
| `courier.domain` | `Courier` aggregate (travel state and store summary), `StoreStatus` |
| `courier.persistence` | `CourierLocationRecord`, `CourierLocationRepository`, `CourierLocationMapper`, `CourierRepository` |
| `courier.messaging` | Kafka inbound Adapter (created only when `courier.kafka.enabled=true`) |
| `store.application` | `StoreEntryDetector`, `StoreCatalogConfiguration` (loads `stores.json`) |
| `store.domain` | `Store`, `StoreCatalog`, `StoreVisit` (entry rules, open/closed visit), `CourierStoreVisits` (per-report transitions), `StoreVisitResult` |
| `store.persistence` | `StoreVisitRepository` (latest visit per store) |
| `common.geo` | `GeoLocation`, `DistanceCalculatorStrategy`, `HaversineDistanceCalculatorStrategy` |
| `common.mapping` | `BaseMapperConfig` |
| `common.persistence` | `BaseEntity` (`created_at`/`updated_at` for every entity) |
| `common.api` | `OpenApiConfiguration` |

Test packages mirror the feature packages ([S003](specs/003-application-structure.md)).

## Store entry rules in code

`StoreVisit` owns both constants: `ENTRY_RADIUS_METERS = 100` (inclusive) and
`MINIMUM_INTERVAL_BETWEEN_ENTRIES = 60 s`. A visit row is one entrance; an open visit
(`exit_time` null) means the courier is inside. `continuesAt(time)` is true when a
re-entry is less than 60 s after the visit's entry; `resume()` then clears the exit
time instead of a new row being written. `CourierStoreVisits` holds the courier's
latest visit per store for one report: `checkProximity` opens or resumes a visit on
the outside→inside transition, closes the open visit on inside→outside, and returns
the changed visit for saving; `outcome()` names the visit opened or resumed and the
store the courier is inside afterwards. `StoreEntryDetector` only loads, loops over
the catalog, and saves.

## Geographic calculation

`GeoLocation` validates finite coordinates in inclusive ranges and is immutable.
`HaversineDistanceCalculatorStrategy` uses R = 6,371,000 m, `double` precision,
`IEEEremainder` for the longitude delta (date-line crossing), and clamps the
intermediate term to [0, 1] against floating-point drift. The formula is recorded
in the [initial decisions](initial-decisions.md#4-distance-calculation).

## Persistence conventions

- Native SQL changeSets in one Liquibase master changelog; `ddl-auto=none`.
- `spring.jpa.open-in-view=false`: services own their transactions, so a database
  connection is held only for the transaction, not for the whole HTTP request.
- Times are stored as `TIMESTAMP(3) WITH TIME ZONE` after millisecond truncation
  in the application.
- Current state and input history are separate tables with no foreign key between
  them; history is never used to rebuild state.
- Every entity extends `BaseEntity`; `created_at`/`updated_at` are set by JPA
  callbacks and by `DEFAULT now()` for native inserts (ADR-015).
- The changelog is a create-only baseline (ADR-015); new schema changes are added
  as incremental changeSets after it, and applied changeSets are never rewritten.

## Engineering conventions

Adapted from the ticketing-reservation reference project on 2026-10-21 and reduced
to what this project uses. The owner's decisions and the active specification take
precedence.

- Controllers handle binding, validation, response mapping, and delegation; they
  call the concrete `CourierService`. Calculations, state transitions, and
  persistence workflows stay out of controllers. No service interface exists only
  for injection or mocking; the distance Strategy is the one approved abstraction.
- Domain rules run without a Spring context. State-dependent invariants live on the
  object that owns the state (`Courier`, `StoreVisit`, `CourierStoreVisits`); no
  blanket mutation APIs let callers bypass them. Mappers translate data only.
- Keep OpenAPI annotations concise: short summaries and response descriptions,
  inferred success schemas. Detailed rules belong in specifications.
- Match test scope to behavior: plain domain tests, Mockito orchestration tests,
  MVC contract tests, and PostgreSQL integration tests for persistence and
  consistency. Concurrency tests must overlap work, use bounded waits, and assert
  persisted invariants. Do not claim backend behavior from MVC binding tests.
- Log identifiers and outcomes with parameterized messages; never full request
  bodies or location histories without a concrete need.
- Record verification evidence and open limitations before marking work complete.
  Do not add security, rate limiting, idempotency keys, or other reference-project
  features without an owner decision.
