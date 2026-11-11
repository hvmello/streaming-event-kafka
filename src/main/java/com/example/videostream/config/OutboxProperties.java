package com.example.videostream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Transactional outbox settings.
 *
 * @param relayEnabled whether the scheduled relay runs; tests turn it off and call {@code runOnce()} directly
 * @param pollInterval delay between the end of one relay pass and the start of the next
 * @param batchSize    maximum rows locked and published per pass
 * @param sendTimeout  how long one send may wait for the broker's acknowledgement; keep it below the
 *                     producer's {@code delivery.timeout.ms}
 */
@ConfigurationProperties("videostream.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("PT0.5S") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT10S") Duration sendTimeout) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch-size must be at least 1");
        }
        if (pollInterval.isNegative() || pollInterval.isZero() || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("poll-interval and send-timeout must be positive");
        }
    }
}
