package com.example.videostream.service.transcode;

import com.example.videostream.config.KafkaTopicConfig;
import com.example.videostream.config.TranscodeConfig;
import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumes {@code video.uploaded} and hands each event to a {@link TranscodeJob} on a worker thread.
 *
 * <p>A transcode can run for far longer than {@code max.poll.interval.ms}. Raising that limit only moves the
 * problem, so the listener thread does not wait for the job: it pauses the record's partition, submits the job
 * and returns. The container keeps calling {@code poll()} on the paused partition, which keeps the consumer in
 * the group and fetches nothing from it. When the job ends, successfully or not, the worker acknowledges the
 * record and resumes the partition. Spring Kafka re-applies a pause after a rebalance, and
 * {@code asyncAcks} lets this thread acknowledge while the consumer thread does the commit.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "videostream.transcode", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TranscodeWorker {

    private final TranscodeJob job;
    private final KafkaListenerEndpointRegistry registry;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public TranscodeWorker(TranscodeJob job, KafkaListenerEndpointRegistry registry, ObjectMapper objectMapper,
                           TranscodeProperties properties) {
        this.job = job;
        this.registry = registry;
        this.objectMapper = objectMapper;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(properties.concurrency(),
                runnable -> Thread.ofPlatform().name("transcode-" + counter.incrementAndGet()).unstarted(runnable));
    }

    @KafkaListener(id = TranscodeConfig.LISTENER_ID, topics = KafkaTopicConfig.VIDEO_UPLOADED,
            groupId = TranscodeConfig.GROUP_ID, containerFactory = TranscodeConfig.CONTAINER_FACTORY)
    public void onUploaded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        VideoUploadedEvent event;
        try {
            event = objectMapper.readValue(record.value(), VideoUploadedEvent.class);
        } catch (JsonProcessingException | RuntimeException e) {
            // A record that cannot be parsed will never parse; dead-letter handling arrives with #12.
            log.error("Dropping unreadable record at {}-{}@{}", record.topic(), record.partition(),
                    record.offset(), e);
            ack.acknowledge();
            return;
        }

        MessageListenerContainer container = registry.getListenerContainer(TranscodeConfig.LISTENER_ID);
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        container.pausePartition(partition);
        try {
            executor.execute(() -> runJob(event, ack, container, partition));
        } catch (RuntimeException e) {
            // Rejected (shutting down): leave the record uncommitted so the next worker retries it.
            container.resumePartition(partition);
            throw e;
        }
    }

    private void runJob(VideoUploadedEvent event, Acknowledgment ack, MessageListenerContainer container,
                        TopicPartition partition) {
        try {
            job.run(event);
        } catch (Throwable t) {
            log.error("Transcode job for video {} threw", event.videoId(), t);
        } finally {
            try {
                ack.acknowledge();
                container.resumePartition(partition);
            } catch (RuntimeException e) {
                // The container stopped or the partition was revoked meanwhile; the next owner re-reads it.
                log.warn("Could not acknowledge or resume {} after the job for video {}", partition,
                        event.videoId(), e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
