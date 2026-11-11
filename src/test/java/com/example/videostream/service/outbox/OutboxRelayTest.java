package com.example.videostream.service.outbox;

import com.example.videostream.config.OutboxProperties;
import com.example.videostream.model.entity.OutboxEvent;
import com.example.videostream.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(true, Duration.ofMillis(500), 50, Duration.ofSeconds(1));
        relay = new OutboxRelay(repository, kafkaTemplate, properties, transactionManager);
    }

    private OutboxEvent row(long id, String key) {
        OutboxEvent event = new OutboxEvent("video.uploaded", key, "{\"n\":" + id + "}", Instant.now());
        event.setId(id);
        return event;
    }

    @Test
    void sendsInIdOrderAndMarksEachRowSent() {
        OutboxEvent first = row(1, "a");
        OutboxEvent second = row(2, "b");
        when(repository.lockUnsent(50)).thenReturn(List.of(first, second));
        List<String> sent = new ArrayList<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sent.add(inv.getArgument(1));
            return CompletableFuture.completedFuture(null);
        });

        int count = relay.runOnce();

        assertThat(count).isEqualTo(2);
        assertThat(sent).containsExactly("a", "b");
        assertThat(first.getSentAt()).isNotNull();
        assertThat(second.getSentAt()).isNotNull();
        verify(kafkaTemplate).send("video.uploaded", "a", "{\"n\":1}");
        verify(transactionManager).commit(any());
    }

    @Test
    void sendFailureStopsThePassRecordsTheErrorAndLeavesLaterRowsUntouched() {
        OutboxEvent first = row(1, "a");
        OutboxEvent failing = row(2, "b");
        OutboxEvent later = row(3, "c");
        when(repository.lockUnsent(50)).thenReturn(List.of(first, failing, later));
        when(kafkaTemplate.send(eq("video.uploaded"), eq("a"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq("video.uploaded"), eq("b"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int count = relay.runOnce();

        assertThat(count).isEqualTo(1);
        assertThat(first.getSentAt()).isNotNull();
        assertThat(failing.getSentAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("broker down");
        assertThat(later.getSentAt()).isNull();
        assertThat(later.getAttempts()).isZero();
        verify(kafkaTemplate, never()).send(eq("video.uploaded"), eq("c"), anyString());
        verify(repository).save(failing);
    }

    @Test
    void repeatedFailuresCountAttemptsAndTruncateTheError() {
        OutboxEvent failing = row(1, "a");
        when(repository.lockUnsent(50)).thenReturn(List.of(failing));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("x".repeat(5000))));

        relay.runOnce();
        relay.runOnce();

        assertThat(failing.getAttempts()).isEqualTo(2);
        assertThat(failing.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);
        assertThat(failing.getSentAt()).isNull();
    }

    @Test
    void failureAfterTheSendPropagatesSoTheTransactionRollsBack() {
        OutboxEvent event = row(1, "a");
        when(repository.lockUnsent(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.save(event)).thenThrow(new IllegalStateException("crash after the ack"));

        assertThatThrownBy(() -> relay.runOnce()).isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void emptyBatchDoesNothing() {
        when(repository.lockUnsent(50)).thenReturn(List.of());

        int count = relay.runOnce();

        assertThat(count).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void scheduledPollDoesNothingWhenTheRelayIsDisabled() {
        OutboxProperties disabled = new OutboxProperties(false, Duration.ofMillis(500), 50, Duration.ofSeconds(1));
        OutboxRelay off = new OutboxRelay(repository, kafkaTemplate, disabled, transactionManager);

        off.poll();

        verify(repository, never()).lockUnsent(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void scheduledPollSwallowsACrashedPass() {
        when(repository.lockUnsent(50)).thenThrow(new IllegalStateException("database down"));

        relay.poll();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
