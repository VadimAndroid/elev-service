# elev-service

[![CI](https://github.com/VadimAndroid/elev-service/actions/workflows/ci.yml/badge.svg?branch=portfolio)](https://github.com/VadimAndroid/elev-service/actions/workflows/ci.yml)

A Spring Boot service in Kotlin that **consumes students (*elever*) from Kafka**, stores them in **PostgreSQL**
and exposes **read endpoints**. It is called by [elev-api](https://github.com/VadimAndroid/elev-api/tree/portfolio), the entry point of the system.

> This is the **`portfolio`** branch. The original school assignment (H2 database, Norwegian README) is on `main`.

| Repository | Role |
|---|---|
| [elev-api](https://github.com/VadimAndroid/elev-api/tree/portfolio) | entry point for clients, port 8080 |
| **elev-service** (this) | stores students in PostgreSQL, port 8081 |
| [elev-infra](https://github.com/VadimAndroid/elev-infra) | `docker-compose.yaml` for Kafka, PostgreSQL and both services, architecture overview |

## Tech stack

Kotlin 2.3 · Java 25 · Spring Boot 4.1 · ktlint · Spring Kafka · Spring Data JPA / Hibernate · PostgreSQL 17 · Flyway ·
Spring Boot Actuator · JUnit 5 · MockMvc · Testcontainers · Awaitility · Docker · GitHub Actions

## API

| Method | Path | Response |
|--------|------|----------|
| GET | `/elever` | `200` with all students from the database (possibly `[]`) |
| GET | `/elever/{id}` | `200` · `404` if not in the database |
| GET | `/actuator/health` | `200` when the app and the database are up |

There is **no POST**. New students arrive only through Kafka.

## Contract with elev-api

| Channel | Direction | Details |
|---|---|---|
| Kafka topic `elever`, consumer group `elev-service` | elev-api → elev-service | key: student id (UUID as text) · value: `{"id":"<uuid>","navn":"<text>","klasse":"<text>"}` |
| Kafka topic `elever.DLT` | elev-service | messages that could not be processed |
| HTTP `GET /elever[/{id}]` | elev-api → elev-service | `200` with JSON, or `404` |

The id from the message is used as is – `elev-service` never generates a new one.
The services share **no code**. Each has its own `Elev` class; only the JSON format binds them.

## Design decisions

### Database and migrations

- The schema is created by **Flyway** (`src/main/resources/db/migration/V1__opprett_elev_tabell.sql`), not by Hibernate.
  Hibernate runs with `ddl-auto=validate` and only checks that `ElevEntity` matches the table.
- An applied migration is never edited (Flyway detects it by checksum). Schema changes go in a new `V2__...sql`.
- `ElevEntity` (JPA, mutable) is kept separate from `Elev` (immutable data class used in JSON).

### No duplicates (idempotent consumer)

Kafka delivers **at least once**, so the same message can be read twice. The id is the **primary key**,
so `save()` does `INSERT` if the student is new and `UPDATE` if it exists. There can only be one row per id.

### Error handling: Dead Letter Topic

A message that can never be processed (a *poison pill*, e.g. broken JSON) must not block the partition.
`KafkaConfig` sets up a `DefaultErrorHandler`:

- temporary errors (e.g. the database is down for a moment) are retried **3 times, 1 second apart**
- JSON errors are **not retried**, because they will never succeed
- after that, the message is published unchanged to **`elever.DLT`**, and the consumer continues with the next message

## Configuration

| Variable | Local default | In Docker |
|----------|---------------|-----------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:29092` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/elevdb` | `jdbc:postgresql://postgres:5432/elevdb` |
| `DB_USER` / `DB_PASSWORD` | `elev` / `elev` | `elev` / `elev` |

## Running

Start Kafka and PostgreSQL from [elev-infra](https://github.com/VadimAndroid/elev-infra) (`docker compose up -d`), then:

```bash
./gradlew bootRun
```

or ▶ next to `fun main` in `ElevServiceApplication.kt`. `requests.http` contains ready-made requests for IntelliJ.
To run everything in Docker, see [elev-infra](https://github.com/VadimAndroid/elev-infra).

## Tests

```bash
./gradlew test      # needs Docker (Testcontainers)
```

Integration tests with **Testcontainers** (real PostgreSQL + Kafka): Kafka message → database → GET ·
the same message twice gives one row · a broken message goes to the DLT and does not block the next one ·
empty list · 404. The containers are started by the tests and never touch the compose database.

## Docker image

Multi-stage `Dockerfile`: the jar is built with Gradle in `eclipse-temurin:25-jdk` and runs in the smaller
`eclipse-temurin:25-jre` as a non-root user.

## CI

`.github/workflows/ci.yml` runs `./gradlew test` on every push and pull request (Testcontainers uses Docker on the runner),
and then checks that the Docker image builds.

## Code style (ktlint)

Checked with [ktlint](https://pinterest.github.io/ktlint/) 1.8 through the Gradle plugin `org.jlleitschuh.gradle.ktlint`.
Rules are in `.editorconfig` (style `intellij_idea`, the same as *Reformat Code* in IntelliJ).

```bash
./gradlew ktlintCheck    # report violations
./gradlew ktlintFormat   # fix what can be fixed automatically
```

`./gradlew build` runs `ktlintCheck` as part of `check`.

## Structure

The service is small, so all classes are in one package:

```
src/main/kotlin/com/example/elevservice/
├── ElevServiceApplication.kt
├── ElevConsumer.kt     – @KafkaListener, JSON -> Elev -> database
├── KafkaConfig.kt      – DefaultErrorHandler, retries and Dead Letter Topic
├── ElevController.kt   – GET /elever and GET /elever/{id}
├── ElevRepository.kt   – JpaRepository
├── ElevEntity.kt       – one row in the table elev
└── Elev.kt             – the JSON contract
```
