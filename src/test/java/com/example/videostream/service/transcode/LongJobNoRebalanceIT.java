package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeConfig;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Done criterion: a job longer than {@code max.poll.interval.ms} does not trigger a rebalance. The poll
 * interval is 5 s and the stand-in transcode blocks for 15 s, so without the pause the broker would evict the
 * consumer and the partition would be revoked and reassigned mid-job.
 */
@TestPropertySource(properties = {
        "videostream.transcode.enabled=true",
        "videostream.transcode.max-poll-interval=PT5S"
})
@Import(LongJobNoRebalanceIT.SlowRunnerConfig.class)
class LongJobNoRebalanceIT extends AbstractPipelineIT {

    private static final Duration JOB_DURATION = Duration.ofSeconds(15);

    static final AtomicInteger ASSIGNED = new AtomicInteger();
    static final AtomicInteger REVOKED = new AtomicInteger();
    static final AtomicInteger JOBS_STARTED = new AtomicInteger();
    static final AtomicInteger ASSIGNED_WHEN_JOB_STARTED = new AtomicInteger();

    @TestConfiguration
    static class SlowRunnerConfig {

        @Bean
        @Primary
        FfmpegRunner slowRunner() {
            return (input, outputDir, renditions) -> {
                JOBS_STARTED.incrementAndGet();
                ASSIGNED_WHEN_JOB_STARTED.set(ASSIGNED.get());
                try {
                    Thread.sleep(JOB_DURATION.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                writeFake(outputDir.resolve(FfmpegRunner.MASTER_PLAYLIST));
                for (Rendition r : renditions) {
                    Files.createDirectories(outputDir.resolve(r.name()));
                    writeFake(outputDir.resolve(r.name()).resolve("index.m3u8"));
                    writeFake(outputDir.resolve(r.name()).resolve("seg_000.ts"));
                }
            };
        }

        @Bean
        ConsumerRebalanceListener countingRebalanceListener() {
            return new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    if (!partitions.isEmpty()) {
                        REVOKED.incrementAndGet();
                    }
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    if (!partitions.isEmpty()) {
                        ASSIGNED.incrementAndGet();
                    }
                }
            };
        }

        private static void writeFake(Path file) throws IOException {
            Files.write(file, "fake".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aJobLongerThanThePollIntervalDoesNotRebalance() throws Exception {
        VideoDTO created = createVideo();
        // The consumer must own its partitions before the event is published.
        await().atMost(Duration.ofSeconds(60)).until(() -> ASSIGNED.get() >= 1);

        upload(created.getId(), "pretend this is a video".getBytes(StandardCharsets.UTF_8));

        Video ready = awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofSeconds(120));

        assertThat(JOBS_STARTED.get()).as("the job ran once").isEqualTo(1);
        assertThat(ready.getHlsManifestKey()).startsWith("hls/" + created.getContentId() + "/")
                .endsWith("/master.m3u8");
        assertThat(qualityLevels(created.getId())).containsExactlyInAnyOrder("720p", "360p");
        // The job outlived max.poll.interval.ms three times over, yet nothing was revoked or reassigned.
        assertThat(REVOKED.get()).as("revocations").isZero();
        assertThat(ASSIGNED.get()).as("assignments after the job started").isEqualTo(ASSIGNED_WHEN_JOB_STARTED.get());

        // The offset was committed once the job finished.
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> offsets = admin
                        .listConsumerGroupOffsets(TranscodeConfig.GROUP_ID)
                        .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                long committed = offsets.values().stream().mapToLong(OffsetAndMetadata::offset).sum();
                assertThat(committed).isGreaterThanOrEqualTo(1);
            });
        }
        assertThat(JOBS_STARTED.get()).as("no redelivery after the commit").isEqualTo(1);
    }
}
