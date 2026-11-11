package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledLeaseRenewerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofMillis(300);

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private ScheduledLeaseRenewer renewer;

    @BeforeEach
    void setUp() {
        TranscodeProperties properties = new TranscodeProperties(true, "ffmpeg", Duration.ofMinutes(1), 6, 1,
                Duration.ofMinutes(5), LEASE, null);
        renewer = new ScheduledLeaseRenewer(videoRepository, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }

    @AfterEach
    void tearDown() {
        renewer.shutdown();
    }

    @Test
    void renewsWithTheAttemptIdWhileOpenAndStopsOnClose() throws Exception {
        when(videoRepository.renewLease(anyLong(), anyString(), any())).thenReturn(1);

        LeaseRenewer.Lease lease = renewer.start(7L, "attempt-1");
        verify(videoRepository, timeout(3000).atLeast(2)).renewLease(7L, "attempt-1", NOW.plus(LEASE));
        lease.close();
        Thread.sleep(150);
        int afterClose = Mockito.mockingDetails(videoRepository).getInvocations().size();
        Thread.sleep(400);

        assertThat(Mockito.mockingDetails(videoRepository).getInvocations()).hasSize(afterClose);
    }

    @Test
    void stopsItselfWhenTheClaimIsLost() throws Exception {
        when(videoRepository.renewLease(anyLong(), anyString(), any())).thenReturn(0);

        renewer.start(7L, "attempt-1");
        verify(videoRepository, timeout(3000)).renewLease(eq(7L), eq("attempt-1"), any());
        Thread.sleep(500);

        verify(videoRepository, times(1)).renewLease(eq(7L), eq("attempt-1"), any());
    }

    @Test
    void aFailedBeatDoesNotStopTheNextOne() {
        when(videoRepository.renewLease(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down")).thenReturn(1);

        renewer.start(7L, "attempt-1");

        verify(videoRepository, timeout(3000).atLeast(2)).renewLease(eq(7L), eq("attempt-1"), any());
    }
}
