package com.example.videostream.service.outbox;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.OutboxEvent;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.OutboxEventRepository;
import com.example.videostream.service.transcode.AbstractPipelineIT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Done criterion of #10: a relay crash between publish and mark-sent loses nothing. Real Postgres and Kafka;
 * the scheduled relay is off so the tests drive {@code runOnce()} themselves, and the transcode worker is off
 * so nothing consumes {@code video.uploaded}.
 */
@TestPropertySource(properties = {
        "videostream.transcode.enabled=false",
        "videostream.outbox.relay-enabled=false"})
class OutboxRelayCrashIT extends AbstractPipelineIT {

    /** Not {@code video.uploaded}: these rows are not upload events and no worker should read them. */
    private static final String SKIP_LOCKED_TOPIC = "outbox-it-skip-locked";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository outboxRepository;

    // Raw type: the auto-configured bean is declared as KafkaTemplate<?, ?>, which a parameterised field would not match.
    @SuppressWarnings("rawtypes")
    @MockitoSpyBean
    private KafkaTemplate kafkaTemplate;

    @Test
    void crashBetweenPublishAndMarkSentLosesNothing() {
        VideoDTO created = createVideo();
        upload(created.getId(), "tiny".getBytes(StandardCharsets.UTF_8));
        String key = created.getContentId();
        try {
            crashBetweenPublishAndMarkSent(created, key);
        } finally {
            // The topic and the database outlive this class. Park the video so that a transcode worker in a
            // later test class, reading video.uploaded from the start, skips these events.
            Video video = videoRepository.findById(created.getId()).orElseThrow();
            video.setStatus(Video.VideoStatus.ERROR);
            videoRepository.save(video);
        }
    }

    @SuppressWarnings("unchecked")
    private void crashBetweenPublishAndMarkSent(VideoDTO created, String key) {
        assertThat(videoRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(Video.VideoStatus.PROCESSING);
        assertThat(rowsFor(key)).hasSize(1).allSatisfy(r -> assertThat(r.getSentAt()).isNull());

        // The first send is acknowledged by the broker and then the process "dies" before mark-sent. An Error is
        // not caught by the relay, like a kill or an OutOfMemoryError, so the pass's transaction rolls back.
        AtomicBoolean crashed = new AtomicBoolean();
        doAnswer(inv -> {
            Object future = inv.callRealMethod();
            ((java.util.concurrent.Future<?>) future).get(30, TimeUnit.SECONDS);
            if (crashed.compareAndSet(false, true)) {
                throw new SimulatedCrash();
            }
            return future;
        }).when(kafkaTemplate).send(any(String.class), any(String.class), any(String.class));
        assertThatThrownBy(() -> relay.runOnce()).isInstanceOf(SimulatedCrash.class);

        assertThat(recordsFor("video.uploaded", key)).as("published once before the crash").hasSize(1);
        assertThat(rowsFor(key)).as("still unsent after the rollback").hasSize(1)
                .allSatisfy(r -> assertThat(r.getSentAt()).isNull());

        // The next pass publishes the row again and marks it sent.
        relay.runOnce();

        assertThat(rowsFor(key)).allSatisfy(r -> assertThat(r.getSentAt()).isNotNull());
        assertThat(recordsFor("video.uploaded", key)).as("at-least-once: the event is on the topic twice, never zero").hasSize(2);
        assertThat(outboxRepository.findAll()).as("every committed outbox row ended up published")
                .allSatisfy(r -> assertThat(r.getSentAt()).isNotNull());
    }

    @SuppressWarnings("unchecked")
    @Test
    void secondRelayPassSkipsRowsLockedByTheFirst() throws Exception {
        String key = "skip-locked-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            outboxRepository.save(new OutboxEvent(SKIP_LOCKED_TOPIC, key, "{\"n\":" + i + "}", Instant.now()));
        }
        CountDownLatch firstIsSending = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (firstCall.getAndSet(false)) {
                firstIsSending.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(kafkaTemplate).send(any(String.class), any(String.class), any(String.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(relay::runOnce);
            assertThat(firstIsSending.await(30, TimeUnit.SECONDS)).isTrue();

            int second = relay.runOnce();

            assertThat(second).as("every unsent row is locked by the first pass").isZero();
            releaseFirst.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(5);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(rowsFor(key)).hasSize(5).allSatisfy(r -> assertThat(r.getSentAt()).isNotNull());
        assertThat(recordsFor(SKIP_LOCKED_TOPIC, key)).as("each row published exactly once").hasSize(5);
    }

    /** Stands in for the process dying between the broker's ack and the commit. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("crash between publish and mark-sent");
        }
    }

    private List<OutboxEvent> rowsFor(String key) {
        return outboxRepository.findAll().stream().filter(r -> key.equals(r.getMessageKey())).toList();
    }

    /** Reads the whole topic with a plain consumer and keeps the records with this key. */
    private List<ConsumerRecord<String, String>> recordsFor(String topic, String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }
}
