package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.entity.TranscodeAttempt;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.TranscodeAttemptRepository;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.InMemoryObjectStore;
import com.example.videostream.service.transcode.TranscodeJob.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeJobTest {

    private static final long VIDEO_ID = 7L;
    private static final String CONTENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String RAW_KEY = "raw/" + CONTENT_ID + "/abc";
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private TranscodeAttemptRepository attemptRepository;

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final TranscodeProperties properties = new TranscodeProperties(true, "ffmpeg",
            Duration.ofMinutes(1), 6, 1, Duration.ofMinutes(5), LEASE, null);
    private final MutableClock clock = new MutableClock(T0);
    private final List<Path> inputsSeen = new ArrayList<>();
    private final List<Path> workDirsSeen = new ArrayList<>();
    private final List<String> heartbeats = new ArrayList<>();
    private final List<String> heartbeatsStopped = new ArrayList<>();
    private Iterator<String> attempts = List.of("attempt-1", "attempt-2").iterator();
    private IOException runnerFailure;
    private boolean runnerInterrupted;
    private Runnable duringRun = () -> { };

    /** Writes the files a real run leaves behind, in an order that puts the master first. */
    private final FfmpegRunner runner = (input, outputDir, renditions) -> {
        inputsSeen.add(input);
        workDirsSeen.add(outputDir.getParent());
        duringRun.run();
        if (runnerInterrupted) {
            throw new InterruptedException("shutdown");
        }
        if (runnerFailure != null) {
            throw runnerFailure;
        }
        write(outputDir.resolve(FfmpegRunner.MASTER_PLAYLIST));
        for (Rendition r : renditions) {
            Files.createDirectories(outputDir.resolve(r.name()));
            write(outputDir.resolve(r.name()).resolve("index.m3u8"));
            write(outputDir.resolve(r.name()).resolve("seg_000.ts"));
            write(outputDir.resolve(r.name()).resolve("seg_001.ts"));
        }
    };

    private final LeaseRenewer leaseRenewer = (videoId, attemptId) -> {
        heartbeats.add(attemptId);
        return () -> heartbeatsStopped.add(attemptId);
    };

    private TranscodeJob job;
    private Video video;

    @BeforeEach
    void setUp() {
        job = new TranscodeJob(videoRepository, attemptRepository, store, runner, properties,
                TransactionOperations.withoutTransaction(), clock, leaseRenewer, () -> attempts.next());
        store.put(RAW_KEY, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "video/mp4");
        store.putOrder.clear();
        video = new Video();
        video.setId(VIDEO_ID);
        video.setContentId(CONTENT_ID);
        video.setStatus(Video.VideoStatus.TRANSCODING);
    }

    private static void write(Path file) throws IOException {
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
    }

    private VideoUploadedEvent event() {
        return new VideoUploadedEvent(VIDEO_ID, CONTENT_ID, "videos", RAW_KEY, 3, "video/mp4", Instant.now());
    }

    private void claimSucceeds() {
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(1);
    }

    @Test
    void uploadsUnderTheAttemptPrefixWithTheMasterLast() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        Outcome outcome = job.run(event());

        String prefix = "hls/" + CONTENT_ID + "/attempt-1/";
        assertThat(outcome).isEqualTo(Outcome.READY);
        assertThat(store.putOrder).hasSize(7).allMatch(k -> k.startsWith(prefix));
        assertThat(store.putOrder.get(store.putOrder.size() - 1)).isEqualTo(prefix + "master.m3u8");
        // Segments before variant playlists.
        int lastSegment = lastIndexOf(".ts");
        int firstVariantPlaylist = store.putOrder.indexOf(prefix + "720p/index.m3u8");
        assertThat(lastSegment).isLessThan(Math.min(firstVariantPlaylist, store.putOrder.indexOf(prefix + "360p/index.m3u8")));
        assertThat(store.putOrder).contains(prefix + "720p/seg_000.ts", prefix + "360p/seg_001.ts");
        verify(videoRepository).markReady(VIDEO_ID, "attempt-1", prefix + "master.m3u8");
        verify(attemptRepository).close(eq("attempt-1"), any(), eq("READY"));
        assertThat(video.getAvailableQualityLevels()).containsExactlyInAnyOrder("720p", "360p");
    }

    private int lastIndexOf(String suffix) {
        int last = -1;
        for (int i = 0; i < store.putOrder.size(); i++) {
            if (store.putOrder.get(i).endsWith(suffix)) {
                last = i;
            }
        }
        return last;
    }

    @Test
    void twoAttemptsNeverShareAPrefix() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        job.run(event());
        job.run(event());

        List<String> first = store.putOrder.stream().filter(k -> k.contains("/attempt-1/")).toList();
        List<String> second = store.putOrder.stream().filter(k -> k.contains("/attempt-2/")).toList();
        assertThat(first).hasSize(7);
        assertThat(second).hasSize(7);
        assertThat(store.size()).isEqualTo(1 + 14);
    }

    @Test
    void aRunnerFailureSetsErrorAndLeavesNoMaster() {
        runnerFailure = new IOException("ffmpeg exited with 1");
        claimSucceeds();
        when(videoRepository.markTranscodeFailed(VIDEO_ID, "attempt-1")).thenReturn(1);

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        verify(videoRepository).markTranscodeFailed(VIDEO_ID, "attempt-1");
        verify(videoRepository, never()).markReady(anyLong(), anyString(), anyString());
        verify(attemptRepository).close(eq("attempt-1"), any(), eq("FAILED"));
        assertThat(store.putOrder).noneMatch(k -> k.endsWith("master.m3u8"));
    }

    @Test
    void anInterruptReturnsTheVideoToProcessingInsteadOfFailingIt() {
        runnerInterrupted = true;
        claimSucceeds();

        Outcome outcome = job.run(event());
        boolean flagRestored = Thread.interrupted();

        assertThat(outcome).isEqualTo(Outcome.INTERRUPTED);
        assertThat(flagRestored).isTrue();
        verify(videoRepository).markTranscodeInterrupted(VIDEO_ID, "attempt-1");
        verify(attemptRepository).close(eq("attempt-1"), any(), eq("INTERRUPTED"));
        verify(videoRepository, never()).markTranscodeFailed(anyLong(), anyString());
        verify(videoRepository, never()).markReady(anyLong(), anyString(), anyString());
        assertThat(store.putOrder).isEmpty();
    }

    @Test
    void aDuplicateAfterReadyIsStaleAndRunsNothing() {
        video.setStatus(Video.VideoStatus.READY);
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.STALE);
        assertThat(inputsSeen).isEmpty();
        assertThat(heartbeats).isEmpty();
        assertThat(store.putOrder).isEmpty();
        verify(attemptRepository, never()).save(any());
        verify(videoRepository, never()).markTranscodeFailed(anyLong(), anyString());
    }

    @Test
    void anEventForAMissingVideoIsStale() {
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.empty());

        assertThat(job.run(event())).isEqualTo(Outcome.STALE);
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void aLiveLeaseHeldByAnotherAttemptIsBusy() {
        video.setTranscodeAttemptId("someone-else");
        video.setTranscodeLeaseUntil(T0.plusSeconds(30));
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.BUSY);
        assertThat(inputsSeen).isEmpty();
        assertThat(store.putOrder).isEmpty();
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void aClaimThatExpiresBetweenTheUpdateAndTheReadIsStillBusyNotStale() {
        // The update saw a live lease and refused; by the time the row is read, the lease is over. The event must
        // not be acknowledged, or the video would stay TRANSCODING with nobody to redeliver it.
        video.setTranscodeLeaseUntil(T0.minusSeconds(1));
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        assertThat(job.run(event())).isEqualTo(Outcome.BUSY);
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void aRowBackInProcessingAfterARefusedClaimIsBusy() {
        video.setStatus(Video.VideoStatus.PROCESSING);
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        assertThat(job.run(event())).isEqualTo(Outcome.BUSY);
    }

    @Test
    void anErroredVideoIsStale() {
        video.setStatus(Video.VideoStatus.ERROR);
        when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenReturn(0);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        assertThat(job.run(event())).isEqualTo(Outcome.STALE);
    }

    @Test
    void theClaimCarriesTheLeaseDeadlineAndTheCurrentTime() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        job.run(event());

        verify(videoRepository).claim(VIDEO_ID, "attempt-1", T0.plus(LEASE), T0);
    }

    @Test
    void aFirstClaimIsLoggedAsNotReclaimedAndALaterOneAsReclaimed() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));
        when(attemptRepository.existsByVideoId(VIDEO_ID)).thenReturn(false, true);

        job.run(event());
        clock.advance(LEASE.plusSeconds(1));
        job.run(event());

        ArgumentCaptor<TranscodeAttempt> saved = ArgumentCaptor.forClass(TranscodeAttempt.class);
        verify(attemptRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TranscodeAttempt::getAttemptId)
                .containsExactly("attempt-1", "attempt-2");
        assertThat(saved.getAllValues()).extracting(TranscodeAttempt::isReclaimed).containsExactly(false, true);
        assertThat(saved.getAllValues().get(1).getStartedAt()).isEqualTo(T0.plus(LEASE).plusSeconds(1));
    }

    @Test
    void theHeartbeatRunsWhileFfmpegRunsAndStopsWhenTheJobEnds() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));
        duringRun = () -> {
            assertThat(heartbeats).containsExactly("attempt-1");
            assertThat(heartbeatsStopped).isEmpty();
        };

        job.run(event());

        assertThat(heartbeatsStopped).containsExactly("attempt-1");
    }

    @Test
    void theHeartbeatStopsWhenTheJobFails() {
        runnerFailure = new IOException("boom");
        claimSucceeds();

        job.run(event());

        assertThat(heartbeatsStopped).containsExactly("attempt-1");
    }

    /** A video row reduced to the columns the claim protocol touches, driven by the repository's conditions. */
    private final class FakeRow {
        String status = "PROCESSING";
        String holder;
        Instant lease;
        String manifest;

        void install() {
            lenient().when(videoRepository.claim(eq(VIDEO_ID), anyString(), any(), any())).thenAnswer(inv -> {
                String attemptId = inv.getArgument(1);
                Instant leaseUntil = inv.getArgument(2);
                Instant now = inv.getArgument(3);
                boolean dead = status.equals("TRANSCODING") && (lease == null || lease.isBefore(now));
                if (status.equals("PROCESSING") || dead) {
                    status = "TRANSCODING";
                    holder = attemptId;
                    lease = leaseUntil;
                    return 1;
                }
                return 0;
            });
            lenient().when(videoRepository.markReady(eq(VIDEO_ID), anyString(), anyString())).thenAnswer(inv -> {
                if (status.equals("TRANSCODING") && inv.getArgument(1).equals(holder)) {
                    status = "READY";
                    manifest = inv.getArgument(2);
                    return 1;
                }
                return 0;
            });
            lenient().when(videoRepository.markTranscodeFailed(eq(VIDEO_ID), anyString())).thenAnswer(inv -> {
                if (status.equals("TRANSCODING") && inv.getArgument(1).equals(holder)) {
                    status = "ERROR";
                    return 1;
                }
                return 0;
            });
            lenient().when(videoRepository.markTranscodeInterrupted(eq(VIDEO_ID), anyString())).thenAnswer(inv -> {
                if (status.equals("TRANSCODING") && inv.getArgument(1).equals(holder)) {
                    status = "PROCESSING";
                    holder = null;
                    lease = null;
                    return 1;
                }
                return 0;
            });
            lenient().when(videoRepository.findById(VIDEO_ID)).thenAnswer(inv -> {
                video.setStatus(Video.VideoStatus.valueOf(status));
                video.setTranscodeAttemptId(holder);
                video.setTranscodeLeaseUntil(lease);
                return Optional.of(video);
            });
        }
    }

    @Test
    void aFencedOutAttemptCannotPublishOverTheAttemptThatTookItsClaim() {
        FakeRow row = new FakeRow();
        row.install();
        // Attempt 1 claims and starts. While it runs its lease expires; attempt 2 takes over and finishes.
        duringRun = () -> {
            duringRun = () -> { };
            clock.advance(LEASE.plusSeconds(1));
            assertThat(job.run(event())).isEqualTo(Outcome.READY);
        };

        Outcome first = job.run(event());

        assertThat(first).isEqualTo(Outcome.SUPERSEDED);
        assertThat(row.status).isEqualTo("READY");
        assertThat(row.manifest).isEqualTo("hls/" + CONTENT_ID + "/attempt-2/master.m3u8");
        verify(videoRepository).markReady(VIDEO_ID, "attempt-1", "hls/" + CONTENT_ID + "/attempt-1/master.m3u8");
        verify(attemptRepository).close(eq("attempt-1"), any(), eq("SUPERSEDED"));
        verify(attemptRepository).close(eq("attempt-2"), any(), eq("READY"));
    }

    @Test
    void aFencedOutAttemptCannotFailTheClaimOfAnother() {
        FakeRow row = new FakeRow();
        row.install();
        duringRun = () -> {
            duringRun = () -> { };
            clock.advance(LEASE.plusSeconds(1));
            // Attempt 2 now holds the claim and is still working (it stays TRANSCODING while this runs).
            claimByHand("attempt-2");
            runnerFailure = new IOException("attempt 1 blew up");
        };
        Outcome first = job.run(event());

        assertThat(first).isEqualTo(Outcome.SUPERSEDED);
        assertThat(row.status).isEqualTo("TRANSCODING");
        assertThat(row.holder).isEqualTo("attempt-2");
        verify(attemptRepository).close(eq("attempt-1"), any(), eq("SUPERSEDED"));
    }

    @Test
    void aFencedOutAttemptCannotResetTheClaimOfAnotherWhenInterrupted() {
        FakeRow row = new FakeRow();
        row.install();
        runnerInterrupted = true;
        duringRun = () -> {
            duringRun = () -> { };
            clock.advance(LEASE.plusSeconds(1));
            claimByHand("attempt-2");
        };

        Outcome first = job.run(event());
        Thread.interrupted();

        assertThat(first).isEqualTo(Outcome.INTERRUPTED);
        assertThat(row.status).isEqualTo("TRANSCODING");
        assertThat(row.holder).isEqualTo("attempt-2");
    }

    private void claimByHand(String attemptId) {
        assertThat(videoRepository.claim(VIDEO_ID, attemptId, clock.instant().plus(LEASE), clock.instant()))
                .isEqualTo(1);
    }

    @Test
    void aMissingRawObjectFailsTheVideo() {
        claimSucceeds();
        when(videoRepository.markTranscodeFailed(VIDEO_ID, "attempt-1")).thenReturn(1);
        VideoUploadedEvent missing = new VideoUploadedEvent(VIDEO_ID, CONTENT_ID, "videos", "raw/none", 3,
                "video/mp4", Instant.now());

        Outcome outcome = job.run(missing);

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        verify(videoRepository).markTranscodeFailed(VIDEO_ID, "attempt-1");
    }

    @Test
    void theTemporaryDirectoryIsDeletedAfterSuccessAndFailure() {
        claimSucceeds();
        when(videoRepository.markReady(anyLong(), anyString(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        job.run(event());
        runnerFailure = new IOException("boom");
        job.run(event());

        assertThat(workDirsSeen).hasSize(2).allMatch(dir -> !Files.exists(dir));
        assertThat(Set.copyOf(workDirsSeen)).hasSize(2);
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
