# streaming-event-kafka roadmap

## Objective

Turn a study project into a small, honest, event-driven video pipeline: upload a real video and play real
HLS output, with failure behaviour that is tested instead of claimed.

**Who it is for:** a reviewer checking whether the author understands Kafka beyond the happy path.

## Starting point (verified 2026-11-09)

| Fact | Evidence |
|---|---|
| Compiles | 27 source files with Maven 3.9.9 |
| Transcoding is simulated | `TranscodingService` sleeps 100 ms and returns empty 1 MiB arrays |
| Configuration is incomplete | `application.properties` holds only the name; the JDBC URL is hardcoded |
| Two application classes, no `mainClass` | Packaging is likely to fail |
| Will not run on one broker | Topics declare `replicas(3)` |
| Almost no tests | One `contextLoads`, in the package of the empty application |
| README overstates the system | Claims scale to "thousands" of users with nothing measured |
| Video bytes travel through Kafka | `KStream<String, byte[]>` with 1 MiB segments, above Kafka's default limit |

## Target architecture

```text
POST /videos -> MinIO (raw, written first) -> Postgres (video + outbox, same transaction)
                                  |
                           outbox relay -> video.uploaded
                                  |
             FFmpeg worker -> MinIO (attempt-scoped HLS, manifest written last)
                                  |
                 video.transcoded | video.failed | *.DLT
                                  |
                  catalog projection -> GET /videos/{id}/master.m3u8
```

Bytes stay in object storage and Kafka carries references (claim check). Long FFmpeg jobs must not exceed
`max.poll.interval.ms`; the worker pauses the partition while it runs. Objects without a database row are
removed by a cleanup job.

## Metrics

| ID | Metric | Definition | Target |
|---|---|---|---|
| K1 | Lost jobs | Accepted uploads with no terminal state after crashing the worker and the relay between publish and mark-sent | 0 |
| K2 | Effect-once | After forced redelivery, each video has one `READY` transition, one catalog row, and a manifest whose segments come from one attempt | 100 % |
| K3 | Re-executions | Transcodes repeated because of at-least-once delivery | Reported |
| K4 | Crash recovery | Time until lag returns to 0 after killing a worker during 1,000 jobs | Reported |
| K5 | Poison handling | Malformed events that reach the DLT without blocking the partition | 100 % |
| K6 | Scaling | Throughput with 1, 2 and 4 workers running real FFmpeg under CPU limits, plus a stub run for broker overhead | Reported |
| K7 | Real output | ffprobe validates two renditions in CI; Playwright confirms hls.js playback | Pass |
| D1 | Time to first run | From clone to playing video with one command | Under 5 minutes |

## Milestones

Estimates are focused hours and are not commitments.

| | Milestone | Hours | Done when |
|---|---|--:|---|
| S0 | **Tell the truth**: honest README, one application class, configuration, replicas 1 in Compose | 3 | Boots on a clean clone against Compose |
| S1 | Real pipeline: claim check, FFmpeg, HLS in MinIO; delete adaptive, session and user code | 14 | K7 passes |
| S2 | Failure behaviour: outbox, idempotent consumer, bounded retries, DLT, crash harness | 12 | K1, K2, K3 and K5 published |
| S3 | Measurement: metrics, lag dashboard, load runs | 6 | K4 and K6 published with the hardware used |

## Out of scope

Live streaming, DRM, CDN, server-side adaptive logic (the HLS player owns it), user accounts.

## Sequencing

S0 is independent and comes first because the current README is public. S1 to S3 start after the
`evidence-gate` v0.1 release, since that project addresses the larger hiring gap.
