package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.repository.VideoRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renews the lease of every running job from one shared scheduler thread, every third of the lease, so two
 * missed beats still leave the claim alive. A beat that finds the claim lost (the update touches no row) stops
 * itself: the job is fenced out and will end as {@code SUPERSEDED}.
 */
@Slf4j
@Component
public class ScheduledLeaseRenewer implements LeaseRenewer {

    private final VideoRepository videoRepository;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Duration lease;
    private final ScheduledExecutorService scheduler;

    public ScheduledLeaseRenewer(VideoRepository videoRepository, TransactionOperations transactions, Clock clock,
                                 TranscodeProperties properties) {
        this.videoRepository = videoRepository;
        this.transactions = transactions;
        this.clock = clock;
        this.lease = properties.lease();
        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("transcode-lease-" + counter.incrementAndGet()).daemon().unstarted(runnable));
    }

    @Override
    public Lease start(Long videoId, String attemptId) {
        long periodMs = Math.max(1, lease.toMillis() / 3);
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> beat(videoId, attemptId, self),
                periodMs, periodMs, TimeUnit.MILLISECONDS);
        self.set(future);
        return () -> future.cancel(false);
    }

    private void beat(Long videoId, String attemptId, AtomicReference<ScheduledFuture<?>> self) {
        try {
            Integer renewed = transactions.execute(status ->
                    videoRepository.renewLease(videoId, attemptId, clock.instant().plus(lease)));
            if (renewed == null || renewed == 0) {
                log.warn("Attempt {} no longer holds the claim on video {}; it stops renewing", attemptId, videoId);
                ScheduledFuture<?> future = self.get();
                if (future != null) {
                    future.cancel(false);
                }
            }
        } catch (RuntimeException e) {
            // A failed beat is retried at the next period; if they all fail the lease runs out, which is the point.
            log.warn("Could not renew the lease of attempt {} for video {}", attemptId, videoId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
