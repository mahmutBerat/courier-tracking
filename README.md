# Courier Tracking

A Java REST API project for tracking courier locations, store entries, and total
travel distance. The project skeleton and local development infrastructure are
ready; domain behavior and REST endpoints have not been implemented yet.
Decisions and open questions are recorded in [initial-decisions.md](initial-decisions.md).

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
port automatically. Domain acceptance tests have not been implemented yet.

The Liquibase changelog is located at
`src/main/resources/db/changelog/db.changelog-master.xml`.
The initial changelog is empty; migrations will be added as the domain schema is
agreed. Automatic Hibernate schema generation is disabled.

## Troubleshooting

- Docker connection error: start Docker Desktop/Engine and check with `docker info`.
- Port already in use: change `DB_PORT` or `SERVER_PORT`.
- Database does not become ready: inspect `make status` and `make logs`.
- Java toolchain error: check your JDK 25 installation and `JAVA_HOME`.

References: the [official Docker image documentation](https://hub.docker.com/_/postgres)
for the PostgreSQL 18 volume layout, and the
[Spring Boot properties reference](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
for Spring configuration.
