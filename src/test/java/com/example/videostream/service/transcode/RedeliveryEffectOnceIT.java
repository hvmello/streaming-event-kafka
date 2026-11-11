package com.example.videostream.service.transcode;

import com.example.videostream.config.KafkaTopicConfig;
import com.example.videostream.config.TranscodeConfig;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.TranscodeAttempt;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.TranscodeAttemptRepository;
import com.example.videostream.service.storage.ObjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Done criteria of #11 against a real Postgres, Kafka and object store, with a stub {@link FfmpegRunner}:
 * <ul>
 *   <li>K2: a {@code video.uploaded} event delivered again after the video is {@code READY} changes nothing;</li>
 *   <li>K2 and K3: an event redelivered while its first attempt is dead (its heartbeat stopped, as after a kill)
 *       waits out the lease, takes the claim over and reaches {@code READY} once; the first attempt, when it
 *       wakes up, is fenced out. K3 reads one re-execution.</li>
 * </ul>
 * The lease is 3 s here (60 s in production). The concurrency is 2 because the stalled first attempt keeps its
 * worker thread, as it would keep its process; the redelivered event needs another one.
 */
@TestPropertySource(properties = {
        "videostream.transcode.enabled=true",
        "videostream.transcode.lease=PT3S",
        "videostream.transcode.concurrency=2"
})
@Import(RedeliveryEffectOnceIT.StubConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedeliveryEffectOnceIT extends AbstractPipelineIT {

    private static final String RAW = "tiny raw video";

    /** Counts runner calls; the first call of a run blocks while {@link #firstCallGate} is set. */
    static final AtomicInteger RUNNER_CALLS = new AtomicInteger();
    static volatile CountDownLatch firstCallGate;
    static final AtomicBoolean SUPPRESS_NEXT_HEARTBEAT = new AtomicBoolean();
    static final List<String> SUPPRESSED_ATTEMPTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        FfmpegRunner stubRunner() {
            return (input, outputDir, renditions) -> {
                int call = RUNNER_CALLS.incrementAndGet();
                CountDownLatch gate = firstCallGate;
                if (call == 1 && gate != null) {
                    try {
                        if (!gate.await(120, TimeUnit.SECONDS)) {
                            throw new IOException("the test never released the first attempt");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                write(outputDir.resolve(FfmpegRunner.MASTER_PLAYLIST));
                for (Rendition r : renditions) {
                    Files.createDirectories(outputDir.resolve(r.name()));
                    write(outputDir.resolve(r.name()).resolve("index.m3u8"));
                    write(outputDir.resolve(r.name()).resolve("seg_000.ts"));
                }
            };
        }

        /** The real renewer, except that the test can stop the heartbeat of the next attempt. */
        @Bean
        @Primary
        LeaseRenewer suppressibleRenewer(ScheduledLeaseRenewer real) {
            return (videoId, attemptId) -> {
                if (SUPPRESS_NEXT_HEARTBEAT.compareAndSet(true, false)) {
                    SUPPRESSED_ATTEMPTS.add(attemptId);
                    return () -> { };
                }
                return real.start(videoId, attemptId);
            };
        }

        private static void write(Path file) throws IOException {
            Files.write(file, "fake".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Autowired
    private TranscodeAttemptRepository attemptRepository;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    // Raw type: the auto-configured bean is declared as KafkaTemplate<?, ?>.
    @SuppressWarnings("rawtypes")
    @Autowired
    private KafkaTemplate kafkaTemplate;

    @MockitoSpyBean
    private TranscodeJob job;

    private final List<Long> videosCreated = new ArrayList<>();

    /** {@code videoId:OUTCOME} for every job run, in completion order. */
    private final List<String> outcomes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        RUNNER_CALLS.set(0);
        firstCallGate = null;
        SUPPRESS_NEXT_HEARTBEAT.set(false);
        SUPPRESSED_ATTEMPTS.clear();
        outcomes.clear();
        doAnswer(inv -> {
            TranscodeJob.Outcome outcome = (TranscodeJob.Outcome) inv.callRealMethod();
            VideoUploadedEvent event = inv.getArgument(0);
            outcomes.add(event.videoId() + ":" + outcome);
            return outcome;
        }).when(job).run(any());
    }

    @AfterEach
    void parkVideos() {
        CountDownLatch gate = firstCallGate;
        if (gate != null) {
            gate.countDown();
        }
        // The topic and the database outlive this class. Park the videos so that a worker in a later test class,
        // reading video.uploaded from the start, skips these events.
        for (Long id : videosCreated) {
            videoRepository.findById(id).ifPresent(video -> {
                video.setStatus(Video.VideoStatus.ERROR);
                videoRepository.save(video);
            });
        }
    }

    private VideoDTO uploadedVideo() {
        VideoDTO created = createVideo();
        videosCreated.add(created.getId());
        upload(created.getId(), RAW.getBytes(StandardCharsets.UTF_8));
        return created;
    }

    @Test
    void aDuplicateDeliveryAfterReadyChangesNothing() throws Exception {
        long reexecutionsBefore = attemptRepository.countReexecutions();
        VideoDTO created = uploadedVideo();
        Video ready = awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofMinutes(2));
        String manifestKey = ready.getHlsManifestKey();
        int runnerCallsBefore = RUNNER_CALLS.get();

        VideoUploadedEvent duplicate = new VideoUploadedEvent(created.getId(), created.getContentId(), "videos",
                ready.getRawObjectKey(), ready.getRawSizeBytes(), "video/mp4", Instant.now());
        sendTwice(created.getContentId(), objectMapper.writeValueAsString(duplicate));
        // Both duplicates were handled, and nothing was done for them.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(outcomes).containsExactly(created.getId() + ":READY", created.getId() + ":STALE",
                        created.getId() + ":STALE"));

        assertThat(RUNNER_CALLS.get()).as("the stub transcoder ran no more").isEqualTo(runnerCallsBefore);
        List<TranscodeAttempt> attempts = attemptRepository.findByVideoIdOrderByStartedAt(created.getId());
        assertThat(attempts).as("one attempt, one READY").hasSize(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo("READY");
        assertThat(attempts.get(0).isReclaimed()).isFalse();
        Video after = videoRepository.findByContentId(created.getContentId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Video.VideoStatus.READY);
        assertThat(after.getHlsManifestKey()).isEqualTo(manifestKey);
        assertThat(objectKeysFor(created.getContentId())).as("every object comes from one attempt")
                .isNotEmpty()
                .allMatch(k -> k.startsWith(manifestKey.substring(0, manifestKey.length() - "master.m3u8".length())));
        assertThat(attemptRepository.countReexecutions() - reexecutionsBefore).as("K3: re-executions").isZero();
    }

    @Test
    void aRedeliveryAfterADeadClaimWaitsOutTheLeaseReclaimsOnceAndFencesTheFirstAttempt() throws Exception {
        long reexecutionsBefore = attemptRepository.countReexecutions();
        firstCallGate = new CountDownLatch(1);
        SUPPRESS_NEXT_HEARTBEAT.set(true);

        VideoDTO created = uploadedVideo();
        // The first attempt claims the video and blocks in the transcoder, with no heartbeat: a dead worker.
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(200))
                .until(() -> RUNNER_CALLS.get() == 1);
        assertThat(videoRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(Video.VideoStatus.TRANSCODING);
        String deadAttempt = attemptRepository.findByVideoIdOrderByStartedAt(created.getId()).get(0).getAttemptId();
        assertThat(SUPPRESSED_ATTEMPTS).containsExactly(deadAttempt);

        // Kill the consumer without acknowledging: the record stays uncommitted and is delivered again.
        var container = registry.getListenerContainer(TranscodeConfig.LISTENER_ID);
        container.stop();
        container.start();

        // The redelivered event finds a live claim, waits, takes the claim over once the lease is gone and
        // finishes.
        Video ready = awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofMinutes(2));
        String manifestKey = ready.getHlsManifestKey();
        List<TranscodeAttempt> attempts = attemptRepository.findByVideoIdOrderByStartedAt(created.getId());
        assertThat(attempts).hasSize(2);
        TranscodeAttempt reclaiming = attempts.get(1);
        assertThat(reclaiming.isReclaimed()).isTrue();
        assertThat(reclaiming.getOutcome()).isEqualTo("READY");
        assertThat(manifestKey).isEqualTo("hls/" + created.getContentId() + "/" + reclaiming.getAttemptId()
                + "/master.m3u8");
        assertThat(attemptRepository.countReexecutions() - reexecutionsBefore).as("K3: re-executions").isEqualTo(1);

        // The dead attempt wakes up and finishes: fenced out, the manifest does not move.
        firstCallGate.countDown();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(attemptRepository.findByVideoIdOrderByStartedAt(created.getId()).get(0).getOutcome())
                        .isEqualTo("SUPERSEDED"));
        Video after = videoRepository.findById(created.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Video.VideoStatus.READY);
        assertThat(after.getHlsManifestKey()).isEqualTo(manifestKey);
        assertThat(after.getTranscodeAttemptId()).isEqualTo(reclaiming.getAttemptId());
        assertThat(attemptRepository.findByVideoIdOrderByStartedAt(created.getId())).hasSize(2);
        assertThat(attemptRepository.countReexecutions() - reexecutionsBefore).as("K3 is unchanged").isEqualTo(1);
        assertThat(objectKeysFor(created.getContentId())).as("the fenced attempt's output is unreferenced")
                .anyMatch(k -> k.contains("/" + deadAttempt + "/"));
        assertThat(RUNNER_CALLS.get()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private void sendTwice(String key, String payload) throws Exception {
        for (int i = 0; i < 2; i++) {
            kafkaTemplate.send(KafkaTopicConfig.VIDEO_UPLOADED, key, payload).get(30, TimeUnit.SECONDS);
        }
    }

    private List<String> objectKeysFor(String contentId) {
        List<String> keys = new ArrayList<>();
        objectStore.forEachPage("hls/" + contentId + "/", page -> page.forEach(o -> keys.add(o.key())));
        return keys;
    }
}
