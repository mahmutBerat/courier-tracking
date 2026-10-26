# Swagger and curl examples

Start the application with `make run`. No authentication is required.

## Demo page

Open [demo.html](http://localhost:8080/demo.html). It is a plain page served from
`src/main/resources/static` that mirrors the curl walkthrough below: one button per
request, editable bodies, a fresh courier ID per visit, a "run steps 1–6" shortcut,
and a custom-location form with a click-to-pick map (Leaflet and OpenStreetMap tiles
from the internet; the five stores are drawn with their 100 m entry circles and the
picked point shows its distance to the nearest store). It covers the success
cases only; error examples stay as curl commands below.
Each step shows its expected status and turns green when the response matches.
A second tab, **Kafka messages**, pairs the `make kafka-send` and `make kafka-send-invalid`
commands (copy buttons, since a browser cannot produce Kafka records) with HTTP
checks that verify consumption: registering `kafka-courier-1`,
querying its distance, and composing a custom `key|json` line for the producer script.

## Swagger UI

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html). Expand an operation,
select **Try it out** if needed, edit its example, and click **Execute**. Register
`courier-1` before using it in location requests. The generated request curl command
can also be copied from Swagger UI.

- [OpenAPI JSON](http://localhost:8080/v3/api-docs)
- [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml)

Use your configured server port if it differs from 8080. Distance queries return **200** for registered couriers and **404** for unknown
IDs. Before any location, the total is zero. Store visits are not exposed as a
resource; they are persisted in `courier_store_visits` (see step 5b for a query), and the
distance response carries the courier's last store, its entry time, the
in-store/out-store status, and whether the last processed report was an entrance.

## Curl walkthrough

Run these commands in order in the same terminal. `-i` displays response status and
headers; error responses remain visible. A fresh ID avoids conflicts with previous
runs. Override the URL when running on a different port.

```sh
BASE_URL=http://localhost:8080
COURIER_ID="curl-demo-$(date +%s)-$$"
```

### 1. Health

Expected: `200`, JSON containing `"status":"UP"` when healthy.

```sh
curl -sS -i "$BASE_URL/actuator/health"
```

### 2. Register a courier

Expected: `201`, empty response body. Repeating this request returns `409` and
preserves the existing courier state.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers" \
  -H 'Content-Type: application/json' \
  -d "{\"courierId\":\"$COURIER_ID\"}"
```

### 3. List couriers

Expected: `200`, an array such as `[{"courierId":"..."}]`, ordered by courier ID.
An empty registry returns `[]`; listing is currently unpaginated.

```sh
curl -sS -i "$BASE_URL/api/v1/couriers"
```

### 4. Submit the first location

Expected: `204`, empty body. This initializes the courier's last location/time,
with total distance zero. Repeating the identical request returns `204` without
another input record or distance update.

The coordinates are those of Ataşehir MMM Migros, so a visit is opened in
`courier_store_visits`; the first later report outside the 100 m area closes it.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers/locations" \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"2026-10-21T12:00:00Z\",\"courier\":\"$COURIER_ID\",\"lat\":40.9923307,\"lng\":29.1244229}"
```

### 5. Submit a newer location

Expected: `204`. The distance from the previous processed location is added to
persisted courier state. Older or same-time distinct inputs are stored but do not
advance that state. Input time is truncated to milliseconds.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers/locations" \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"2026-10-21T12:01:00Z\",\"courier\":\"$COURIER_ID\",\"lat\":40.986106,\"lng\":29.1161293}"
```

These are the coordinates of Novada MMM Migros: the courier left Ataşehir and
entered Novada, so a second visit is recorded for Novada.

### 5b. Re-enter Ataşehir within one minute

Expected: `204`. The courier moved out of Ataşehir's area in step 5 and is back
inside now. 90 s have passed since its previous Ataşehir entry at 12:00:00, so this
re-entry is a new entrance and opens a second Ataşehir visit. A re-entry less than
60 s after 12:01:30 (after leaving again) would not be an entrance: the visit would
resume and no row would be added.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers/locations" \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"2026-10-21T12:01:30Z\",\"courier\":\"$COURIER_ID\",\"lat\":40.9923307,\"lng\":29.1244229}"
```

Inspect persisted visits with `make db-shell` (`exit_time` is null while the courier
is inside):

```sql
SELECT courier_id, store_name, entry_time, exit_time FROM courier_store_visits ORDER BY entry_time;
```

### 6. Query distance

Expected: `200`, with `courierId` and numeric `distanceMeters` containing the
accumulated Haversine distance over the reports above. The response reads
persisted courier state without replaying input history.

```sh
curl -sS -i --get "$BASE_URL/api/v1/couriers/distance" \
  --data-urlencode "courierId=$COURIER_ID"
```

Querying an unregistered ID returns `404` with the uniform error body:

```sh
curl -sS -i --get "$BASE_URL/api/v1/couriers/distance" \
  --data-urlencode "courierId=$COURIER_ID-unknown"
```

```json
{"status":404,"error":"Not Found","code":"COURIER_NOT_FOUND","message":"Courier not found.","path":"/api/v1/couriers/distance"}
```

## Error examples

Every error has the same shape: `status`, `error`, `code`, `message`, `path`, and
`fieldErrors` (only for validation failures, each with `field` and `message`).

Unknown courier: `404`, no location stored. Do not register the `-unknown` ID.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers/locations" \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"2026-10-21T12:00:00Z\",\"courier\":\"$COURIER_ID-unknown\",\"lat\":40.99,\"lng\":29.12}"
```

Blank courier ID: `400`.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers" \
  -H 'Content-Type: application/json' -d '{"courierId":" "}'
```

Invalid latitude: `400`, no location stored. Latitude must be finite in [-90,90]
and longitude finite in [-180,180]. All four location fields are required.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers/locations" \
  -H 'Content-Type: application/json' \
  -d "{\"time\":\"2026-10-21T12:00:00Z\",\"courier\":\"$COURIER_ID\",\"lat\":91,\"lng\":29.12}"
```

```json
{"status":400,"error":"Bad Request","code":"VALIDATION_FAILED","message":"Request validation failed.","path":"/api/v1/couriers/locations","fieldErrors":[{"field":"lat","message":"must be less than or equal to 90"}]}
```

Missing distance query parameter: `400`.

```sh
curl -sS -i "$BASE_URL/api/v1/couriers/distance"
```

Non-JSON creation request: `415`.

```sh
curl -sS -i -X POST "$BASE_URL/api/v1/couriers" \
  -H 'Content-Type: text/plain' -d 'courier-1'
```

These commands create test data in the local database. Reusing an ID does not reset
its distance; use a fresh ID for a new travel scenario. `make down` preserves data.

## Kafka messages

With the application running (`make run` also starts the broker), the same report can be produced as a
message on the `courier-locations` topic. Key: the courier ID. Value: the HTTP body.

```json
{"time":"2026-10-21T12:00:00Z","courier":"kafka-courier-1","lat":40.9923307,"lng":29.1244229}
```

The message goes through the same validation and the same transaction as the POST
endpoint, so duplicates, ordering, and store visits behave identically.
`make kafka-send` registers `kafka-courier-1` and produces the walkthrough above
(steps 4, 5, and 5b) from `scripts/kafka/sample-locations.jsonl`; afterwards the
distance query returns about `1963.31` meters and `courier_store_visits` holds three
visits. Produce your own lines with the console producer wrapper:

```sh
printf 'kafka-courier-1|{"time":"2026-10-21T12:03:00Z","courier":"kafka-courier-1","lat":40.986106,"lng":29.1161293}\n' > /tmp/more.jsonl
scripts/kafka/produce-locations.sh /tmp/more.jsonl
```

Messages that can never succeed are not retried; the application skips them and
logs one INFO line each (watch the `make run` terminal):

```sh
make kafka-send-invalid   # malformed JSON, lat 95, unregistered courier
```

```text
INFO ... Skipped unprocessable location message topic=courier-locations partition=2 offset=9 key=kafka-courier-1 exception=tools.jackson.core.exc.StreamReadException; publishing such records to a dead-letter topic is planned
INFO ... Skipped unprocessable location message topic=courier-locations partition=2 offset=10 key=kafka-courier-1 exception=jakarta.validation.ConstraintViolationException; ...
INFO ... Skipped unprocessable location message topic=courier-locations partition=1 offset=1 key=kafka-courier-unknown exception=com.mbi.couriertracking.courier.application.CourierNotFoundException; ...
```

Nothing is stored for these three lines, and the next valid message is processed.

### Store status in distance responses

Distance responses also contain `lastStoreName` (the last physically entered
store, retained after exit), `lastStoreEntryTime` (input time of that entry), and
`currentStatus` (`in-store` or `out-store`), and `isEntry` (`true` only when the
last processed report opened a new visit; `false` after a resumed visit, an exit, or
a report that changed nothing). Before the first entry both store fields are `null`,
status is `out-store`, and `isEntry` is `false`. Status reflects the latest processed
input, not wall-clock presence: a report whose time is not newer than the courier's
last processed report is stored but changes neither distance nor status, and the demo
custom form says so after such a submission. The custom demo button
**increase time + send location** adds 60 seconds and queries distance only after a
successful submission.
