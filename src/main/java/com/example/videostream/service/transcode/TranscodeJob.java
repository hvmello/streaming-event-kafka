package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.event.VideoUploadedEvent;
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
 * <p>Every attempt writes under {@code hls/{contentId}/{attemptId}/}, so two attempts at the same video (a
 * redelivery, or a worker that lost its partition) never overwrite each other's files. The master playlist is
 * uploaded last, so a reader that finds it finds everything it references. Only the attempt whose
 * {@code TRANSCODING -> READY} update succeeds is referenced from the database.
 */
@Slf4j
@Component
public class TranscodeJob {

    /** How an attempt ended. */
    public enum Outcome {
        /** The video is {@code READY} and points at this attempt's master playlist. */
        READY,
        /** The video was not {@code PROCESSING}: a duplicate or stale event, nothing was done. */
        STALE,
        /** The attempt failed and the video is {@code ERROR}. */
        FAILED,
        /** The attempt finished but another attempt had already moved the video; its output is unreferenced. */
        SUPERSEDED,
        /**
         * The thread was interrupted (shutdown). The video is back to {@code PROCESSING} and the caller must not
         * acknowledge the record, so it is redelivered and claimed again.
         */
        INTERRUPTED
    }

    private static final String HLS_PREFIX = "hls/";

    private final VideoRepository videoRepository;
    private final ObjectStore objectStore;
    private final FfmpegRunner ffmpegRunner;
    private final TranscodeProperties properties;
    private final TransactionOperations transactions;
    private final Supplier<String> attemptIds;

    @Autowired
    public TranscodeJob(VideoRepository videoRepository, ObjectStore objectStore, FfmpegRunner ffmpegRunner,
                        TranscodeProperties properties, TransactionOperations transactions) {
        this(videoRepository, objectStore, ffmpegRunner, properties, transactions,
                () -> UUID.randomUUID().toString());
    }

    TranscodeJob(VideoRepository videoRepository, ObjectStore objectStore, FfmpegRunner ffmpegRunner,
                 TranscodeProperties properties, TransactionOperations transactions,
                 Supplier<String> attemptIds) {
        this.videoRepository = videoRepository;
        this.objectStore = objectStore;
        this.ffmpegRunner = ffmpegRunner;
        this.properties = properties;
        this.transactions = transactions;
        this.attemptIds = attemptIds;
    }

    public Outcome run(VideoUploadedEvent event) {
        Long videoId = event.videoId();
        Integer claimed = transactions.execute(status -> videoRepository.markTranscoding(videoId));
        if (claimed == null || claimed == 0) {
            log.info("Skipping video {}: it is not PROCESSING (duplicate or stale event)", videoId);
            return Outcome.STALE;
        }

        String attemptId = attemptIds.get();
        String prefix = HLS_PREFIX + event.contentId() + "/" + attemptId + "/";
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("transcode-" + videoId + "-");
            Path input = workDir.resolve("input");
            Path output = Files.createDirectory(workDir.resolve("out"));

            objectStore.get(event.key(), input);
            List<Rendition> renditions = properties.renditions();
            ffmpegRunner.transcode(input, output, renditions);
            uploadOutput(output, prefix);
            return publish(videoId, prefix + FfmpegRunner.MASTER_PLAYLIST, renditions);
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

    private Outcome publish(Long videoId, String manifestKey, List<Rendition> renditions) {
        Boolean won = transactions.execute(status -> {
            if (videoRepository.markReady(videoId, manifestKey) == 0) {
                return false;
            }
            videoRepository.findById(videoId).ifPresent(video -> {
                video.setAvailableQualityLevels(new HashSet<>(renditions.stream().map(Rendition::name).toList()));
                videoRepository.save(video);
            });
            return true;
        });
        if (Boolean.TRUE.equals(won)) {
            log.info("Video {} is READY: {}", videoId, manifestKey);
            return Outcome.READY;
        }
        log.warn("Video {} left TRANSCODING while this attempt ran; its output {} stays unreferenced",
                videoId, manifestKey);
        return Outcome.SUPERSEDED;
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
            transactions.executeWithoutResult(status -> videoRepository.markTranscodeInterrupted(videoId));
        } catch (RuntimeException e) {
            log.error("Could not return video {} to PROCESSING", videoId, e);
        } finally {
            Thread.currentThread().interrupt();
        }
        return Outcome.INTERRUPTED;
    }

    private Outcome fail(Long videoId, String attemptId, Exception cause) {
        log.error("Transcode attempt {} for video {} failed", attemptId, videoId, cause);
        try {
            transactions.executeWithoutResult(status -> videoRepository.markTranscodeFailed(videoId));
        } catch (RuntimeException e) {
            log.error("Could not mark video {} as ERROR", videoId, e);
        }
        return Outcome.FAILED;
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
