# streaming-event-kafka

A study project exploring Kafka Streams, virtual threads and the Java Flow API around a video streaming domain.

> **Status: study code, being rebuilt.** The project compiles, but transcoding is simulated and the
> application has not been verified end to end against a broker. See the
> [roadmap](https://github.com/hvmello/streaming-event-kafka/blob/develop/docs/ROADMAP.md) for what comes next.

## What is in the code

- Spring Boot 3.5 on Java 21, with a JPA entity and repository for videos
- Claim-check upload: `POST /api/videos/{id}/upload` streams the file to an S3-compatible object store, records the object key on the video row and publishes a small JSON reference to `video.uploaded`
- Transcode worker: a consumer of `video.uploaded` downloads the raw object, runs FFmpeg once to produce two HLS renditions (720p and 360p), uploads them under `hls/{contentId}/{attemptId}/` with `master.m3u8` last, and marks the video `READY`. The consumer pauses its partition while a job runs, so a long transcode does not cause a rebalance
- Playback: `GET /api/videos/{id}/hls/{path}` streams the playlists and segments of a `READY` video from the object store (same origin, no CORS), and `video.playbackUrl` points at the master playlist. A small hls.js player lives at `http://localhost:8080/player.html?video={id}`
- A scheduled cleaner that deletes raw objects no video row references
- A Kafka Streams stream on `video-metadata` (to be replaced) and topic configuration
- A REST controller for the video catalog and upload

## What is simulated

| Part | Reality today |
|---|---|
| Transcoding | Real FFmpeg HLS output at two renditions, run inside the application process. A failed job goes to `ERROR` with no retry (#12). Handling is **effect-once** (#11, see below). The playlists and segments are served through the application (bandwidth is the cost; a presigned redirect or CDN, and range requests, are later) |
| Commit and publish | The upload commits the `PROCESSING` row and an outbox row in one transaction, and a relay publishes the outbox to Kafka and only then marks it sent. A crash between publish and mark-sent publishes the event again, so delivery is **at-least-once**: a duplicate `video.uploaded` is possible (the transcode worker ignores it, see effect-once below), a lost event is not. `202` now means "committed and will be published": when Kafka is down the upload still returns `202` (it no longer returns `502`) and the event goes out when the broker is back. One undeliverable row blocks the relay to keep order (poison handling is #12), and sent rows are not deleted yet |
| Scale and performance | Nothing has been measured |

## Effect-once event handling

Kafka delivers at least once, so `video.uploaded` can arrive again after a rebalance, a crash or an outbox retry. The dedupe key is the **video row**, not the message: the effect that must happen once is "the video becomes `READY` with one manifest".

- **Claim.** An attempt takes the video with a conditional update, from `PROCESSING` or from a dead `TRANSCODING` claim (its lease ran out), and stores its attempt id on the row. That id is a fencing token.
- **Lease.** While FFmpeg runs, the job renews the claim every `lease / 3`. A worker that dies stops renewing, so its claim is taken over after at most one `videostream.transcode.lease` (default `PT60S`).
- **Fencing.** `READY`, `ERROR` and the return to `PROCESSING` only succeed for the attempt that holds the claim. An attempt that lost it ends as `SUPERSEDED` and its HLS output stays unreferenced (the orphan cleaner only covers raw uploads, so superseded output is not deleted yet).
- **`BUSY`.** A duplicate that finds a live claim keeps its partition paused, is not acknowledged, and runs again after `lease / 3`, until the other attempt finishes (then it is a no-op) or its lease runs out (then it takes over). Records behind it on that partition wait, for at most one lease.
- **K2 (duplicates):** after one `READY`, extra deliveries change nothing. `RedeliveryEffectOnceIT` publishes the same event twice more and checks one `READY`, one manifest, one attempt id under the prefix.
- **K3 (re-executions per video):** every claim is a row in `transcode_attempts`; `TranscodeAttemptRepository.countReexecutions()` is attempts minus distinct videos, and each claim logs `attempt N for video X (reclaimed=...)`. The same IT kills a claim (no heartbeat, consumer stopped without acknowledging) and reads K3 = 1, with the first attempt fenced out when it wakes up. A stalled heartbeat longer than the lease also counts as a re-execution: that is the honest number.
- The lease compares timestamps from each worker's clock, so it must stay far above the clock skew between hosts.

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

Transcoding is tuned with `videostream.transcode.*` (`timeout`, `concurrency`, `max-poll-interval`, `lease`, `segment-seconds` and the `renditions` list).

The cleaner is tuned with `videostream.storage.orphan-grace` (default `PT1H`, how old an unreferenced object must be before it is deleted; keep it far above the longest request) and `videostream.storage.cleanup-interval` (default `PT10M`).

## Run locally

Requires Docker, JDK 21 and FFmpeg (the `ffmpeg` binary on the `PATH`, or set `FFMPEG_PATH`). The end-to-end transcode test also needs `ffprobe`; it is skipped without it, and fails instead when `REQUIRE_FFMPEG=true`, as in CI. The playback test (`PlaybackIT`) also needs Google Chrome installed, because Playwright's bundled Chromium cannot play H.264 or AAC; it is skipped without Chrome, and fails instead when `REQUIRE_BROWSER=true`, as in CI (which sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`, so no other browser is downloaded). Run it with `./mvnw verify`.

```
docker compose up -d --wait
./mvnw spring-boot:run
docker compose down
```

`compose.yaml` starts Postgres, a single-node Kafka (KRaft) and an S3-compatible object store (SeaweedFS, S3 API on port 8333) on the standard ports, for an application running on the host. Override the object store host port with `S3_PORT` and its credentials with `S3_ACCESS_KEY` and `S3_SECRET_KEY`; if you change the port, set `S3_ENDPOINT` to match. Kafka needs host port 9092 free: the broker advertises `localhost:9092`, so remapping the port would give clients an address they cannot reach. Override the Postgres host port with `POSTGRES_PORT` and the database credentials with `DB_USERNAME` and `DB_PASSWORD`, in the shell or a git-ignored `.env` file. If you change the Postgres port, set `DB_URL` to match. Use `docker compose down -v` to drop the database volume too.

## Known problems

- The `video-metadata` stream is a placeholder with fake quality levels, and nothing publishes to it
- Transcoding runs inside the application, so FFmpeg competes with the API for CPU; raw files up to 512 MB are copied to a temp directory
- There are almost no tests

These are tracked as [issues](https://github.com/hvmello/streaming-event-kafka/issues), grouped by milestone.
