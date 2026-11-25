# elev-service

En Spring Boot-tjeneste i Kotlin som **leser elever fra Kafka**, lagrer dem i en **H2-database** og tilbyr **GET-endepunkter** for å hente dem.

Tjenesten blir kalt av [elev-api](https://github.com/VadimAndroid/elev-api), som er inngangen for klienten.

## Rolle i systemet

Systemet består av to **mikrotjenester** i hvert sitt repository:

| Tjeneste | Repository | Port | Ansvar |
|---|---|---|---|
| elev-api | [VadimAndroid/elev-api](https://github.com/VadimAndroid/elev-api) | 8080 | Eneste inngang for klienten. Validerer, genererer ID, sender til Kafka, henter fra `elev-service`. |
| **elev-service** | dette | 8081 | Leser elever fra Kafka og lagrer dem i databasen. Tilbyr GET-endepunkter. |
| Kafka | `docker-compose.yaml` i elev-api | 9092 | Meldingskø mellom tjenestene. |

```
Opprette elev (asynkront):
Klient ──▶ elev-api ──Kafka (topic "elever")──▶ elev-service ──▶ database (H2)

Hente elever (synkront):
Klient ──▶ elev-api ──HTTP GET──▶ elev-service ──▶ database (H2)
```

Tjenestene deler **ingen kode**. Hver har sin egen `Elev`-klasse. Det eneste som binder dem sammen, er **kontrakten** under.

## Kontrakt med elev-api

### Kafka (elev-service leser)

| | |
|---|---|
| Topic | `elever` |
| Consumer group | `elev-service` |
| Nøkkel | elevens ID (UUID som tekst) |
| Verdi | JSON: `{ "id": "<uuid>", "navn": "<tekst>", "klasse": "<tekst>" }` |

ID-en fra meldingen brukes som den er. `elev-service` genererer **ikke** en ny ID.

### HTTP (elev-service svarer)

| Metode | Sti | Svar |
|---|---|---|
| GET | `/elever` | `200` med alle elever fra databasen (eventuelt `[]`) |
| GET | `/elever/{id}` | `200` med eleven · `404` hvis den ikke finnes |

`elev-service` har **ingen POST**. Nye elever kommer bare fra Kafka.

## Slik fungerer mottaket

1. `@KafkaListener` lytter på topicen `elever` i consumer group `elev-service`.
2. Meldingen leses som tekst og gjøres om til en `Elev` med Jackson.
3. Eleven lagres i databasen med samme ID.
4. ID-en logges etter at lagringen er fullført.

Kafka husker hvor consumer groupen har lest til. Elever som opprettes mens `elev-service` er nede, blir lest og lagret når den starter igjen.

## Database

`elev-service` bruker **H2** med **fillagring** og **Spring Data JPA**, så elevene finnes også etter omstart.

| Del | Fil | Hva den gjør |
|-----|-----|--------------|
| Tilkobling | `application.properties` | `jdbc:h2:file:./data/elevdb` – lagres på disk |
| Tabell | `src/main/resources/db/migration/V1__opprett_elev_tabell.sql` | Flyway-migrering som lager tabellen `elev` |
| Entity | `ElevEntity.kt` | JPA-klassen som tilsvarer én rad i `elev` |
| Repository | `ElevRepository.kt` | `JpaRepository<ElevEntity, UUID>` – gir `save`, `findById`, `findAll` |

Tabellen `elev`:

| Kolonne  | Type           | Merknad |
|----------|----------------|---------|
| `id`     | `UUID`         | Primærnøkkel. Kommer fra Kafka-meldingen. |
| `navn`   | `VARCHAR(255)` | `NOT NULL` |
| `klasse` | `VARCHAR(255)` | `NOT NULL` |

Databasefilen ligger i `data/elevdb.mv.db`. Mappen `data/` er i `.gitignore`, fordi den inneholder data og ikke kode.

### Migreringer med Flyway

Tabellene lages av **Flyway**, ikke av Hibernate. Hibernate står på `ddl-auto=validate` og sjekker bare at `ElevEntity` stemmer med tabellen.

- En migrering som allerede er kjørt, skal **ikke** endres.
- Endringer i skjemaet gjøres med en ny fil, for eksempel `V2__legg_til_epost.sql`.

### Ingen duplikater

Samme melding kan bli lest flere ganger fra Kafka. Siden `id` er primærnøkkel og kommer fra meldingen, blir `save()` en `UPDATE` hvis eleven finnes fra før. Det kan derfor bare finnes én rad per elev-ID.

### Se innholdet i databasen

1. Start `elev-service`.
2. Åpne <http://localhost:8081/h2-console>
3. Logg inn med **JDBC URL** `jdbc:h2:file:./data/elevdb`, **User Name** `sa`, tomt passord.
4. Kjør for eksempel `SELECT * FROM elev;`

**Starte med tom database:** stopp `elev-service` og slett mappen `data/`.

## Konfigurasjon

`src/main/resources/application.properties`:

```properties
server.port=8081
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=elev-service
spring.kafka.consumer.auto-offset-reset=earliest
elev.kafka.topic=elever
spring.datasource.url=jdbc:h2:file:./data/elevdb
```

## Kjøre

**Forutsetninger:** Java 25, Docker Desktop.

**1. Start Kafka** – med `docker compose up -d` i [elev-api](https://github.com/VadimAndroid/elev-api).

**2. Start elev-service:**

```bash
./gradlew bootRun
```

eller ▶ ved `fun main` i `ElevServiceApplication.kt` i IntelliJ. Åpne dette repoet som et eget prosjekt, så er arbeidsmappen riktig og databasefilen havner i `data/` her.

**3. Start elev-api** – se README i [elev-api](https://github.com/VadimAndroid/elev-api).

## Teste

- **IntelliJ:** åpne `requests.http` og trykk ▶ ved hver forespørsel.
- **Enhetstester:** `./gradlew test` – testene bruker en egen H2-database i minnet og starter ikke Kafka-lytteren.

## Kodestil (ktlint)

Koden sjekkes med [ktlint](https://pinterest.github.io/ktlint/) via Gradle-pluginen `org.jlleitschuh.gradle.ktlint`.
Reglene ligger i `.editorconfig` (stilen `intellij_idea`, samme som *Reformat Code* i IntelliJ).

```bash
./gradlew ktlintCheck    # viser feil
./gradlew ktlintFormat   # retter det som kan rettes automatisk
```

`./gradlew build` kjører `ktlintCheck` automatisk.

## Struktur

Tjenesten er liten, så alle klassene ligger i én pakke:

```
src/main/kotlin/com/example/elevservice/
├── ElevServiceApplication.kt
├── ElevConsumer.kt     – @KafkaListener, JSON -> Elev -> database
├── ElevController.kt   – GET /elever og GET /elever/{id}
├── ElevRepository.kt   – JpaRepository
├── ElevEntity.kt       – én rad i tabellen elev
└── Elev.kt             – det som sendes over Kafka og HTTP
```
