# streaming-event-kafka

A study project exploring Kafka Streams, virtual threads and the Java Flow API around a video streaming domain.

> **Status: study code, being rebuilt.** The project compiles, but transcoding is simulated and the
> application has not been verified end to end against a broker. See the
> [roadmap](https://github.com/hvmello/streaming-event-kafka/blob/develop/docs/ROADMAP.md) for what comes next.

## What is in the code

- Spring Boot 3.5 on Java 21, with JPA entities and repositories for videos, users and viewing sessions
- A Kafka Streams topology and topic configuration for a transcoding pipeline
- REST controllers for the catalog and for streaming
- Segment delivery using `java.util.concurrent.Flow` and virtual threads

## What is simulated

| Part | Reality today |
|---|---|
| Transcoding | `TranscodingService` waits 100 ms and returns empty 1 MiB arrays |
| Segment storage | Generated in code; no object store is involved |
| Scale and performance | Nothing has been measured |

## Known problems

- Video bytes travel through Kafka, which the rebuild replaces with object storage and references
- The database URL is hardcoded and topics require three brokers
- There are two application classes, and almost no tests

These are tracked as [issues](https://github.com/hvmello/streaming-event-kafka/issues), grouped by milestone.
