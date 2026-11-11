# streaming-event-kafka

A study project exploring Kafka Streams, virtual threads and the Java Flow API around a video streaming domain.

> **Status: study code, being rebuilt.** The project compiles, but transcoding is simulated and the
> application has not been verified end to end against a broker. See the
> [roadmap](https://github.com/hvmello/streaming-event-kafka/blob/develop/docs/ROADMAP.md) for what comes next.

## What is in the code

- Spring Boot 3.5 on Java 21, with a JPA entity and repository for videos
- Claim-check upload: `POST /api/videos/{id}/upload` streams the file to an S3-compatible object store, records the object key on the video row and publishes a small JSON reference to `video.uploaded`
- A scheduled cleaner that deletes raw objects no video row references
- A Kafka Streams stream on `video-metadata` (to be replaced) and topic configuration
- A REST controller for the video catalog and upload

## What is simulated

| Part | Reality today |
|---|---|
| Transcoding | Not implemented. Video bytes stay out of Kafka: only references travel on `video.uploaded` and nothing consumes them yet |
| Commit and publish | The row commit and the Kafka publish are two separate writes. A crash between them leaves a `PROCESSING` row with no event (the transactional outbox, #10, closes this) |
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
| `S3_ENDPOINT` | `http://localhost:8333` | Object store (S3 API) endpoint; path-style access |
| `S3_REGION` | `us-east-1` | Signing region |
| `S3_BUCKET` | `videostream` | Bucket for raw uploads; created at startup if missing |
| `S3_ACCESS_KEY` | `local-access-key` | Object store access key (local default only; set it in any deployment) |
| `S3_SECRET_KEY` | `local-secret-key` | Object store secret key (local default only; set it in any deployment) |
| `UPLOAD_MAX_SIZE` | `512MB` | Maximum upload size (file and request) |

The cleaner is tuned with `videostream.storage.orphan-grace` (default `PT1H`, how old an unreferenced object must be before it is deleted; keep it far above the longest request) and `videostream.storage.cleanup-interval` (default `PT10M`).

## Run locally

Requires Docker and JDK 21.

```
docker compose up -d --wait
./mvnw spring-boot:run
docker compose down
```

`compose.yaml` starts Postgres, a single-node Kafka (KRaft) and an S3-compatible object store (SeaweedFS, S3 API on port 8333) on the standard ports, for an application running on the host. Override the object store host port with `S3_PORT` and its credentials with `S3_ACCESS_KEY` and `S3_SECRET_KEY`; if you change the port, set `S3_ENDPOINT` to match. Kafka needs host port 9092 free: the broker advertises `localhost:9092`, so remapping the port would give clients an address they cannot reach. Override the Postgres host port with `POSTGRES_PORT` and the database credentials with `DB_USERNAME` and `DB_PASSWORD`, in the shell or a git-ignored `.env` file. If you change the Postgres port, set `DB_URL` to match. Use `docker compose down -v` to drop the database volume too.

## Known problems

- Nothing consumes `video.uploaded` yet, and the `video-metadata` stream is a placeholder
- There are almost no tests

These are tracked as [issues](https://github.com/hvmello/streaming-event-kafka/issues), grouped by milestone.
