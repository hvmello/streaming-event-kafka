package com.example.videostream.service.outbox;

import com.example.videostream.config.OutboxProperties;
import com.example.videostream.model.entity.OutboxEvent;
import com.example.videostream.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes the rows of the outbox to Kafka, oldest first.
 *
 * <p>One pass locks a batch of unsent rows ({@code FOR UPDATE SKIP LOCKED}), sends each one and waits for the
 * broker's acknowledgement, then marks it sent. The marks only become visible when the pass's transaction
 * commits, so a row is always published before it is recorded as sent. A crash after a send and before the
 * commit rolls the marks back and the next pass publishes the row again: delivery is at-least-once. A
 * duplicate is possible, a loss is not.
 *
 * <p>A failed send stops the pass without skipping ahead, so the order on the topic follows the commit order.
 * The failed row's attempt count and error are saved with the rows already sent in the same pass (the failed
 * row cannot be updated from a second transaction, because this one holds its lock); the failed row is retried
 * on the next pass. Any other exception (a crash) rolls the whole pass back.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       OutboxProperties properties, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Scheduled entry point; does nothing when {@code videostream.outbox.relay-enabled} is false. */
    @Scheduled(fixedDelayString = "${videostream.outbox.poll-interval:PT0.5S}")
    void poll() {
        if (!properties.relayEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Outbox relay pass failed; unsent rows stay in the outbox and are retried", e);
        }
    }

    /**
     * Runs one pass.
     *
     * @return the number of rows published and marked sent
     */
    public int runOnce() {
        Integer sent = transactionTemplate.execute(status -> publishBatch());
        return sent == null ? 0 : sent;
    }

    private int publishBatch() {
        List<OutboxEvent> batch = repository.lockUnsent(properties.batchSize());
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(event, e);
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                fail(event, e);
                break;
            }
            event.setSentAt(Instant.now());
            repository.save(event);
            sent++;
        }
        return sent;
    }

    private void fail(OutboxEvent event, Exception e) {
        log.warn("Could not publish outbox event {} to {}; stopping this pass", event.getId(), event.getTopic(), e);
        event.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        repository.save(event);
    }
}
