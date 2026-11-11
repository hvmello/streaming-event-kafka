package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.entity.TranscodeAttempt;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.TranscodeAttemptRepository;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.ObjectStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * One transcode attempt for one {@code video.uploaded} event. It knows nothing about Kafka, so it is tested
 * with an in-memory store and a stub {@link FfmpegRunner}.
 *
 * <p><b>Effect-once.</b> Kafka delivers at least once, so the same event can arrive again after a rebalance, a
 * crash or an outbox retry. The dedupe key is the video row, not the message: what must happen once is "this
 * video becomes {@code READY} with one manifest", and the row is where that is decided.
 * <ul>
 *   <li>An attempt first <em>claims</em> the row with a conditional update: from {@code PROCESSING}, or from
 *       {@code TRANSCODING} when the previous claim's lease has run out (its worker died). The claim stores the
 *       attempt id, which is a fencing token.</li>
 *   <li>While FFmpeg runs, a {@link LeaseRenewer} extends the lease, so a live claim is never taken over.</li>
 *   <li>Every later update ({@code READY}, {@code ERROR}, back to {@code PROCESSING}) also requires that token.
 *       An attempt that lost its claim (a stalled heartbeat, a zombie after a rebalance) updates no row and ends
 *       as {@code SUPERSEDED}; whatever it wrote stays unreferenced.</li>
 *   <li>Every attempt writes under {@code hls/{contentId}/{attemptId}/}, so attempts never overwrite each other,
 *       and the master playlist goes last, so a reader that finds it finds everything it references.</li>
 * </ul>
 * The work itself may run more than once (K3 counts it in {@code transcode_attempts}); its effect, the
 * {@code READY} transition and the manifest it points at, happens once. A duplicate that finds the video in a
 * terminal state is {@code STALE}; one that finds a live claim is {@code BUSY}.
 */
@Slf4j
@Component
public class TranscodeJob {

    /** How an attempt ended. */
    public enum Outcome {
        /** The video is {@code READY} and points at this attempt's master playlist. */
        READY,
        /** The video is already finished (or gone): a duplicate or stale event, nothing was done. */
        STALE,
        /**
         * Another attempt holds a claim on the video that is still open, so nothing was done. The caller must not acknowledge
         * the record and should run the event again later: the claim either finishes (then the retry is
         * {@code STALE}) or its lease runs out (then the retry takes it over).
         */
        BUSY,
        /** The attempt failed and the video is {@code ERROR}. */
        FAILED,
        /** The attempt lost its claim to another attempt; its output is unreferenced. */
        SUPERSEDED,
        /**
         * The thread was interrupted (shutdown). The video is back to {@code PROCESSING} and the caller must not
         * acknowledge the record, so it is redelivered and claimed again.
         */
        INTERRUPTED
    }

    private static final String HLS_PREFIX = "hls/";

    private final VideoRepository videoRepository;
    private final TranscodeAttemptRepository attemptRepository;
    private final ObjectStore objectStore;
    private final FfmpegRunner ffmpegRunner;
    private final TranscodeProperties properties;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final LeaseRenewer leaseRenewer;
    private final Supplier<String> attemptIds;

    @Autowired
    public TranscodeJob(VideoRepository videoRepository, TranscodeAttemptRepository attemptRepository,
                        ObjectStore objectStore, FfmpegRunner ffmpegRunner, TranscodeProperties properties,
                        TransactionOperations transactions, Clock clock, LeaseRenewer leaseRenewer) {
        this(videoRepository, attemptRepository, objectStore, ffmpegRunner, properties, transactions, clock,
                leaseRenewer, () -> UUID.randomUUID().toString());
    }

    TranscodeJob(VideoRepository videoRepository, TranscodeAttemptRepository attemptRepository,
                 ObjectStore objectStore, FfmpegRunner ffmpegRunner, TranscodeProperties properties,
                 TransactionOperations transactions, Clock clock, LeaseRenewer leaseRenewer,
                 Supplier<String> attemptIds) {
        this.videoRepository = videoRepository;
        this.attemptRepository = attemptRepository;
        this.objectStore = objectStore;
        this.ffmpegRunner = ffmpegRunner;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.leaseRenewer = leaseRenewer;
        this.attemptIds = attemptIds;
    }

    public Outcome run(VideoUploadedEvent event) {
        Long videoId = event.videoId();
        String attemptId = attemptIds.get();
        if (!claim(videoId, attemptId)) {
            return unclaimed(videoId);
        }

        String prefix = HLS_PREFIX + event.contentId() + "/" + attemptId + "/";
        Path workDir = null;
        try (LeaseRenewer.Lease lease = leaseRenewer.start(videoId, attemptId)) {
            workDir = Files.createTempDirectory("transcode-" + videoId + "-");
            Path input = workDir.resolve("input");
            Path output = Files.createDirectory(workDir.resolve("out"));

            objectStore.get(event.key(), input);
            List<Rendition> renditions = properties.renditions();
            ffmpegRunner.transcode(input, output, renditions);
            uploadOutput(output, prefix);
            return publish(videoId, attemptId, prefix + FfmpegRunner.MASTER_PLAYLIST, renditions);
        } catch (InterruptedException e) {
            return interrupted(videoId, attemptId);
        } catch (IOException | RuntimeException e) {
            if (causedByInterrupt(e)) {
                return interrupted(videoId, attemptId);
            }
            return fail(videoId, attemptId, e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** Takes the claim and logs the attempt, in one transaction. */
    private boolean claim(Long videoId, String attemptId) {
        Instant now = clock.instant();
        Boolean claimed = transactions.execute(status -> {
            if (videoRepository.claim(videoId, attemptId, now.plus(properties.lease()), now) == 0) {
                return false;
            }
            boolean reclaimed = attemptRepository.existsByVideoId(videoId);
            attemptRepository.save(new TranscodeAttempt(videoId, attemptId, now, reclaimed));
            log.info("Claimed video {} as attempt {} (reclaimed={})", videoId, attemptId, reclaimed);
            return true;
        });
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * The claim was refused. A video that is still unfinished ({@code TRANSCODING}, or {@code PROCESSING} after a
     * race with a release) means BUSY: the claim was alive when the update ran, and may have expired since, so the
     * lease is deliberately not re-read here; the retry decides. Anything else means the event has nothing left
     * to do.
     */
    private Outcome unclaimed(Long videoId) {
        Boolean busy = transactions.execute(status -> videoRepository.findById(videoId)
                .map(video -> video.getStatus() == Video.VideoStatus.TRANSCODING
                        || video.getStatus() == Video.VideoStatus.PROCESSING)
                .orElse(false));
        if (Boolean.TRUE.equals(busy)) {
            log.info("Video {} is held by a transcode claim that is still open; the event will be retried", videoId);
            return Outcome.BUSY;
        }
        log.info("Skipping video {}: it is not claimable (duplicate or stale event)", videoId);
        return Outcome.STALE;
    }

    private Outcome publish(Long videoId, String attemptId, String manifestKey, List<Rendition> renditions) {
        Boolean won = transactions.execute(status -> {
            if (videoRepository.markReady(videoId, attemptId, manifestKey) == 0) {
                closeAttempt(attemptId, Outcome.SUPERSEDED);
                return false;
            }
            videoRepository.findById(videoId).ifPresent(video -> {
                video.setAvailableQualityLevels(new HashSet<>(renditions.stream().map(Rendition::name).toList()));
                videoRepository.save(video);
            });
            closeAttempt(attemptId, Outcome.READY);
            return true;
        });
        if (Boolean.TRUE.equals(won)) {
            log.info("Video {} is READY: {}", videoId, manifestKey);
            return Outcome.READY;
        }
        log.warn("Attempt {} no longer holds the claim on video {}; its output {} stays unreferenced",
                attemptId, videoId, manifestKey);
        return Outcome.SUPERSEDED;
    }

    private void closeAttempt(String attemptId, Outcome outcome) {
        attemptRepository.close(attemptId, clock.instant(), outcome.name());
    }

    private static boolean causedByInterrupt(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /** Returns the video to PROCESSING. The interrupt flag is cleared meanwhile, so the database calls work. */
    private Outcome interrupted(Long videoId, String attemptId) {
        Thread.interrupted();
        try {
            log.warn("Transcode attempt {} for video {} was interrupted; returning it to PROCESSING",
                    attemptId, videoId);
            transactions.executeWithoutResult(status -> {
                videoRepository.markTranscodeInterrupted(videoId, attemptId);
                closeAttempt(attemptId, Outcome.INTERRUPTED);
            });
        } catch (RuntimeException e) {
            log.error("Could not return video {} to PROCESSING", videoId, e);
        } finally {
            Thread.currentThread().interrupt();
        }
        return Outcome.INTERRUPTED;
    }

    /** Fails the video, unless this attempt no longer holds the claim: then another attempt owns the outcome. */
    private Outcome fail(Long videoId, String attemptId, Exception cause) {
        log.error("Transcode attempt {} for video {} failed", attemptId, videoId, cause);
        try {
            Boolean failed = transactions.execute(status -> {
                boolean held = videoRepository.markTranscodeFailed(videoId, attemptId) > 0;
                closeAttempt(attemptId, held ? Outcome.FAILED : Outcome.SUPERSEDED);
                return held;
            });
            return Boolean.FALSE.equals(failed) ? Outcome.SUPERSEDED : Outcome.FAILED;
        } catch (RuntimeException e) {
            log.error("Could not mark video {} as ERROR", videoId, e);
            return Outcome.FAILED;
        }
    }

    /**
     * Uploads every file under {@code output}, whatever FFmpeg named it: media segments first, then the variant
     * playlists, then the master playlist.
     */
    private void uploadOutput(Path output, String prefix) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(output)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.<Path>comparingInt(this::uploadRank).thenComparing(Path::toString))
                    .toList();
        }
        for (Path file : files) {
            String relative = output.relativize(file).toString().replace('\\', '/');
            try (InputStream content = Files.newInputStream(file)) {
                objectStore.put(prefix + relative, content, Files.size(file), contentType(relative));
            }
        }
    }

    /** 0 for media, 1 for variant playlists, 2 for the master playlist. */
    private int uploadRank(Path file) {
        String name = file.getFileName().toString();
        if (name.equals(FfmpegRunner.MASTER_PLAYLIST)) {
            return 2;
        }
        return name.endsWith(".m3u8") ? 1 : 0;
    }

    private static String contentType(String name) {
        if (name.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (name.endsWith(".ts")) {
            return "video/mp2t";
        }
        return "application/octet-stream";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete temporary directory {}", dir, e);
        }
    }
}
