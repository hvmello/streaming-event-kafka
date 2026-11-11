package com.example.videostream.service.transcode;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Done criterion: ffprobe validates two renditions of a real transcode. A 12 s sample is generated with FFmpeg
 * at test time, uploaded over HTTP, transcoded by the worker, downloaded back from the object store and probed.
 *
 * <p>Needs the {@code ffmpeg} and {@code ffprobe} binaries. Locally the test is skipped without them; with
 * {@code REQUIRE_FFMPEG=true}, as in CI, a missing binary fails the test instead, so it cannot pass silently.
 */
@TestPropertySource(properties = "videostream.transcode.enabled=true")
class TranscodeEndToEndIT extends AbstractPipelineIT {

    @Autowired
    private S3Client s3;

    @TempDir
    Path dir;

    @Test
    void twoRenditionsPassFfprobe() throws Exception {
        requireFfmpeg();
        Path sample = dir.resolve("sample.mp4");
        run(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "12", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                sample.toString()));

        VideoDTO created = createVideo();
        upload(created.getId(), Files.readAllBytes(sample));
        Video ready = awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofMinutes(3));

        String manifestKey = ready.getHlsManifestKey();
        assertThat(manifestKey).matches("hls/" + created.getContentId() + "/[0-9a-f-]{36}/master\\.m3u8");
        assertThat(qualityLevels(created.getId())).containsExactlyInAnyOrder("720p", "360p");

        // Download the attempt prefix as it is in the bucket.
        String prefix = manifestKey.substring(0, manifestKey.length() - "master.m3u8".length());
        Path hls = dir.resolve("hls");
        for (S3Object object : s3.listObjectsV2Paginator(b -> b.bucket("videostream").prefix(prefix)).contents()) {
            Path target = hls.resolve(object.key().substring(prefix.length()));
            Files.createDirectories(target.getParent());
            s3.getObject(GetObjectRequest.builder().bucket("videostream").key(object.key()).build(), target);
        }

        // The master lists exactly the two variants, and each variant has the declared height.
        List<String> variants = Files.readAllLines(hls.resolve("master.m3u8")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertThat(variants).hasSize(2);
        List<Integer> heights = new ArrayList<>();
        for (String variant : variants) {
            Path playlist = hls.resolve(variant);
            assertThat(playlist).exists();
            String probe = probe(List.of("ffprobe", "-v", "error", "-select_streams", "v",
                    "-show_entries", "stream=height", "-of", "csv=p=0", playlist.toString()));
            List<Integer> streamHeights = probe.lines().filter(l -> !l.isBlank())
                    .map(String::trim).map(Integer::parseInt).toList();
            assertThat(streamHeights).as("video streams of " + variant).hasSize(1);
            heights.addAll(streamHeights);
            // Every playlist is a complete VOD playlist with the segments of a 12 s clip at 6 s.
            assertThat(Files.readString(playlist)).contains("#EXT-X-ENDLIST");
            assertThat(Files.readString(playlist).lines().filter(l -> l.endsWith(".ts")).count()).isEqualTo(2);
        }
        assertThat(new TreeSet<>(heights)).containsExactly(360, 720);
    }

    @Test
    void aVideoWithoutAudioReachesReady() throws Exception {
        requireFfmpeg();
        Path sample = dir.resolve("silent.mp4");
        run(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-t", "12", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an",
                sample.toString()));

        VideoDTO created = createVideo();
        upload(created.getId(), Files.readAllBytes(sample));
        Video ready = awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofMinutes(3));

        assertThat(ready.getHlsManifestKey()).endsWith("/master.m3u8");
        assertThat(qualityLevels(created.getId())).containsExactlyInAnyOrder("720p", "360p");
    }

    private static void requireFfmpeg() {
        boolean present = onPath("ffmpeg") && onPath("ffprobe");
        if ("true".equalsIgnoreCase(System.getenv("REQUIRE_FFMPEG"))) {
            assertThat(present).as("REQUIRE_FFMPEG=true but ffmpeg or ffprobe is not on the PATH").isTrue();
        } else {
            assumeTrue(present, "ffmpeg and ffprobe are not on the PATH");
        }
    }

    private static boolean onPath(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void run(List<String> command) throws Exception {
        probe(command);
    }

    private static String probe(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as(String.join(" ", command)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", command) + "\n" + output).isZero();
        return output;
    }
}
