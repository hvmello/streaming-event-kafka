# streaming-event-kafka

A study project exploring Kafka Streams, virtual threads and the Java Flow API around a video streaming domain.

> **Status: study code, being rebuilt.** The project compiles, but transcoding is simulated and the
> application has not been verified end to end against a broker. See the
> [roadmap](https://github.com/hvmello/streaming-event-kafka/blob/develop/docs/ROADMAP.md) for what comes next.

## What is in the code

- Spring Boot 3.5 on Java 21, with a JPA entity and repository for videos
- A Kafka Streams topology and topic configuration for a transcoding pipeline
- A REST controller for the video catalog and upload

## What is simulated

| Part | Reality today |
|---|---|
| Transcoding | `TranscodingService` waits 100 ms and returns empty 1 MiB arrays |
| Scale and performance | Nothing has been measured |

## Configuration

Settings live in `src/main/resources/application.properties`; these environment variables override the local defaults:

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/videostream` | JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password (local default only; set it in any deployment) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `KAFKA_TOPIC_REPLICAS` | `1` | Replication factor of the pipeline topics; must not exceed the broker count |

## Run locally

Requires Docker and JDK 21.

```
docker compose up -d --wait
./mvnw spring-boot:run
docker compose down
```

`compose.yaml` starts Postgres and a single-node Kafka (KRaft) on the standard ports, for an application running on the host. Kafka needs host port 9092 free: the broker advertises `localhost:9092`, so remapping the port would give clients an address they cannot reach. Override the Postgres host port with `POSTGRES_PORT` and the database credentials with `DB_USERNAME` and `DB_PASSWORD`, in the shell or a git-ignored `.env` file. If you change the Postgres port, set `DB_URL` to match. Use `docker compose down -v` to drop the database volume too.

## Known problems

- Video bytes travel through Kafka, which the rebuild replaces with object storage and references
- There are almost no tests

These are tracked as [issues](https://github.com/hvmello/streaming-event-kafka/issues), grouped by milestone.
