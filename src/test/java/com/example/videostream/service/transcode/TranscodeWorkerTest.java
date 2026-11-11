package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeConfig;
import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeWorkerTest {

    private static final TopicPartition PARTITION = new TopicPartition("video.uploaded", 3);

    private final TranscodeJob job = mock(TranscodeJob.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private TranscodeWorker worker;

    @BeforeEach
    void setUp() {
        when(registry.getListenerContainer(TranscodeConfig.LISTENER_ID)).thenReturn(container);
        // A 300 ms lease makes the BUSY retry come after 100 ms.
        TranscodeProperties properties = new TranscodeProperties(true, "ffmpeg", Duration.ofMinutes(1), 6, 1,
                Duration.ofMinutes(5), Duration.ofMillis(300), null);
        worker = new TranscodeWorker(job, registry, objectMapper, properties);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private ConsumerRecord<String, String> record() throws Exception {
        VideoUploadedEvent event = new VideoUploadedEvent(7L, "content", "videos", "raw/content/abc", 3,
                "video/mp4", Instant.now());
        return new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), 42L, "content",
                objectMapper.writeValueAsString(event));
    }

    @Test
    void aStaleEventIsAcknowledgedAndThePartitionResumes() throws Exception {
        when(job.run(any())).thenReturn(TranscodeJob.Outcome.STALE);

        worker.onUploaded(record(), ack);

        verify(ack, timeout(2000)).acknowledge();
        verify(container, timeout(2000)).resumePartition(PARTITION);
        verify(container).pausePartition(PARTITION);
        verify(job, times(1)).run(any());
    }

    @Test
    void aBusyEventStaysPausedUnacknowledgedAndRunsAgainUntilItEnds() throws Exception {
        when(job.run(any())).thenReturn(TranscodeJob.Outcome.BUSY, TranscodeJob.Outcome.BUSY,
                TranscodeJob.Outcome.STALE);

        worker.onUploaded(record(), ack);

        // Two BUSY results in a row: the event is submitted again each time, and nothing is acknowledged or
        // resumed until the third run, the one that ends the wait.
        verify(job, timeout(5000).times(3)).run(any());
        verify(ack, timeout(2000).times(1)).acknowledge();
        verify(container, timeout(2000).times(1)).resumePartition(PARTITION);
        verify(container, times(1)).pausePartition(PARTITION);
    }

    @Test
    void whileTheEventIsBusyNothingIsAcknowledgedOrResumed() throws Exception {
        when(job.run(any())).thenReturn(TranscodeJob.Outcome.BUSY);

        worker.onUploaded(record(), ack);

        verify(job, timeout(5000).atLeast(2)).run(any());
        verify(ack, never()).acknowledge();
        verify(container, never()).resumePartition(any());
    }

    @Test
    void anInterruptedJobIsNotAcknowledgedButThePartitionResumes() throws Exception {
        when(job.run(any())).thenReturn(TranscodeJob.Outcome.INTERRUPTED);

        worker.onUploaded(record(), ack);

        verify(container, timeout(2000)).resumePartition(PARTITION);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aFailedAcknowledgementStillResumesThePartition() throws Exception {
        when(job.run(any())).thenReturn(TranscodeJob.Outcome.STALE);
        org.mockito.Mockito.doThrow(new IllegalStateException("Unexpected ack")).when(ack).acknowledge();

        worker.onUploaded(record(), ack);

        verify(container, timeout(2000)).resumePartition(PARTITION);
    }
}
