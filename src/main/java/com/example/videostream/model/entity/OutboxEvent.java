package com.example.videostream.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An event waiting to be published to Kafka. The row is written in the same database transaction as the state
 * change it announces, so the two commit or roll back together; {@code OutboxRelay} publishes it afterwards.
 *
 * <p>The identity column gives the publish order. {@code sentAt} stays null until the relay has published the
 * event, and the index on {@code (sentAt, id)} keeps the "oldest unsent" query cheap.
 */
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_events_unsent", columnList = "sentAt, id"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    /** Longest {@code lastError} kept; longer messages are truncated. */
    public static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String messageKey;

    /** The JSON value to publish. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    /** Null until the event has been published and acknowledged by the broker. */
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    public OutboxEvent(String topic, String messageKey, String payload, Instant createdAt) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    /** Records a failed publish attempt. */
    public void recordFailure(String error) {
        attempts++;
        if (error == null) {
            lastError = null;
        } else {
            lastError = error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        }
    }
}
