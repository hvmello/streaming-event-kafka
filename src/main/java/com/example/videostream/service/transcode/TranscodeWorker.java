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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
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
 *
 * <p>A job that finds another attempt's live claim ends {@code BUSY}: this happens after a rebalance, while the
 * previous owner is still running (a zombie) or has died but its lease has not run out. The worker then keeps the
 * partition paused, does not acknowledge, and runs the same event again after a third of the lease. The wait is
 * bounded by one lease: the zombie finishes (the retry is {@code STALE}) or the lease runs out (the retry takes
 * the claim over). Records behind it on that partition wait meanwhile.
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
    private final ScheduledExecutorService retryScheduler;
    private final Duration busyRetryDelay;

    public TranscodeWorker(TranscodeJob job, KafkaListenerEndpointRegistry registry, ObjectMapper objectMapper,
                           TranscodeProperties properties) {
        this.job = job;
        this.registry = registry;
        this.objectMapper = objectMapper;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(properties.concurrency(),
                runnable -> Thread.ofPlatform().name("transcode-" + counter.incrementAndGet()).unstarted(runnable));
        AtomicInteger retryCounter = new AtomicInteger();
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("transcode-retry-" + retryCounter.incrementAndGet()).daemon()
                        .unstarted(runnable));
        this.busyRetryDelay = properties.lease().dividedBy(3);
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
        boolean finished = true;
        boolean acknowledge = true;
        try {
            TranscodeJob.Outcome outcome = job.run(event);
            // An interrupted job did not finish: leave the record unacknowledged so it is redelivered.
            acknowledge = outcome != TranscodeJob.Outcome.INTERRUPTED;
            if (outcome == TranscodeJob.Outcome.BUSY) {
                acknowledge = false;
                finished = !retryLater(event, ack, container, partition);
            }
        } catch (Throwable t) {
            log.error("Transcode job for video {} threw", event.videoId(), t);
        } finally {
            if (finished) {
                // The partition must resume even when the acknowledgement fails (the container stopped, the
                // partition was revoked, or the batch it belonged to is gone): the record is then redelivered
                // and handled as a duplicate.
                if (acknowledge) {
                    try {
                        ack.acknowledge();
                    } catch (RuntimeException e) {
                        log.warn("Could not acknowledge {} after the job for video {}; it will be redelivered",
                                partition, event.videoId(), e);
                    }
                }
                try {
                    container.resumePartition(partition);
                } catch (RuntimeException e) {
                    log.warn("Could not resume {} after the job for video {}", partition, event.videoId(), e);
                }
            }
        }
    }

    /**
     * Schedules the event to run again; the record stays unacknowledged and the partition stays paused.
     *
     * @return false if it could not be scheduled (shutting down), so the caller must not leave the partition paused
     */
    private boolean retryLater(VideoUploadedEvent event, Acknowledgment ack, MessageListenerContainer container,
                               TopicPartition partition) {
        log.info("Video {} is claimed by another attempt; running its event again in {}", event.videoId(),
                busyRetryDelay);
        try {
            retryScheduler.schedule(() -> {
                try {
                    executor.execute(() -> runJob(event, ack, container, partition));
                } catch (RejectedExecutionException e) {
                    log.warn("Not retrying video {}: the worker is shutting down", event.videoId());
                }
            }, busyRetryDelay.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Not retrying video {}: the worker is shutting down", event.videoId());
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        retryScheduler.shutdownNow();
        executor.shutdownNow();
    }
}
