package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.InMemoryObjectStore;
import com.example.videostream.service.transcode.TranscodeJob.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeJobTest {

    private static final long VIDEO_ID = 7L;
    private static final String CONTENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String RAW_KEY = "raw/" + CONTENT_ID + "/abc";

    @Mock
    private VideoRepository videoRepository;

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final TranscodeProperties properties = new TranscodeProperties(true, "ffmpeg",
            java.time.Duration.ofMinutes(1), 6, 1, java.time.Duration.ofMinutes(5), null);
    private final List<Path> inputsSeen = new ArrayList<>();
    private final List<Path> workDirsSeen = new ArrayList<>();
    private Iterator<String> attempts = List.of("attempt-1", "attempt-2").iterator();
    private IOException runnerFailure;
    private boolean runnerInterrupted;

    /** Writes the files a real run leaves behind, in an order that puts the master first. */
    private final FfmpegRunner runner = (input, outputDir, renditions) -> {
        inputsSeen.add(input);
        workDirsSeen.add(outputDir.getParent());
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

    private TranscodeJob job;
    private Video video;

    @BeforeEach
    void setUp() {
        job = new TranscodeJob(videoRepository, store, runner, properties, TransactionOperations.withoutTransaction(),
                () -> attempts.next());
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

    @Test
    void uploadsUnderTheAttemptPrefixWithTheMasterLast() {
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);
        when(videoRepository.markReady(anyLong(), anyString())).thenReturn(1);
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
        verify(videoRepository).markReady(VIDEO_ID, prefix + "master.m3u8");
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
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);
        when(videoRepository.markReady(anyLong(), anyString())).thenReturn(1);
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
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        verify(videoRepository).markTranscodeFailed(VIDEO_ID);
        verify(videoRepository, never()).markReady(anyLong(), anyString());
        assertThat(store.putOrder).noneMatch(k -> k.endsWith("master.m3u8"));
    }

    @Test
    void anInterruptReturnsTheVideoToProcessingInsteadOfFailingIt() {
        runnerInterrupted = true;
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);

        Outcome outcome = job.run(event());
        boolean flagRestored = Thread.interrupted();

        assertThat(outcome).isEqualTo(Outcome.INTERRUPTED);
        assertThat(flagRestored).isTrue();
        verify(videoRepository).markTranscodeInterrupted(VIDEO_ID);
        verify(videoRepository, never()).markTranscodeFailed(anyLong());
        verify(videoRepository, never()).markReady(anyLong(), anyString());
        assertThat(store.putOrder).isEmpty();
    }

    @Test
    void aStaleEventDoesNothing() {
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(0);

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.STALE);
        assertThat(inputsSeen).isEmpty();
        assertThat(store.putOrder).isEmpty();
        verify(videoRepository, never()).markTranscodeFailed(anyLong());
    }

    @Test
    void anAttemptThatLosesTheFinalUpdateIsSuperseded() {
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);
        when(videoRepository.markReady(anyLong(), anyString())).thenReturn(0);

        Outcome outcome = job.run(event());

        assertThat(outcome).isEqualTo(Outcome.SUPERSEDED);
        verify(videoRepository, never()).findById(VIDEO_ID);
        verify(videoRepository, never()).markTranscodeFailed(anyLong());
    }

    @Test
    void aMissingRawObjectFailsTheVideo() {
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);
        VideoUploadedEvent missing = new VideoUploadedEvent(VIDEO_ID, CONTENT_ID, "videos", "raw/none", 3,
                "video/mp4", Instant.now());

        Outcome outcome = job.run(missing);

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        verify(videoRepository).markTranscodeFailed(VIDEO_ID);
    }

    @Test
    void theTemporaryDirectoryIsDeletedAfterSuccessAndFailure() {
        when(videoRepository.markTranscoding(VIDEO_ID)).thenReturn(1);
        when(videoRepository.markReady(anyLong(), anyString())).thenReturn(1);
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));

        job.run(event());
        runnerFailure = new IOException("boom");
        job.run(event());

        assertThat(workDirsSeen).hasSize(2).allMatch(dir -> !Files.exists(dir));
        assertThat(Set.copyOf(workDirsSeen)).hasSize(2);
    }
}
