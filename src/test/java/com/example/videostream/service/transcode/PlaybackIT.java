package com.example.videostream.service.transcode;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Done criterion: a sample video plays in a browser. A 12 s sample is generated with FFmpeg, uploaded and
 * transcoded by the worker; the playlists and segments are fetched over the real server, and headless Google
 * Chrome plays the video through hls.js.
 *
 * <p>Playwright's bundled Chromium has no H.264 or AAC, so the test uses the installed Google Chrome. Locally it
 * is skipped without ffmpeg or Chrome; with {@code REQUIRE_FFMPEG=true} and {@code REQUIRE_BROWSER=true}, as in
 * CI, a missing one fails the test instead.
 */
@TestPropertySource(properties = "videostream.transcode.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlaybackIT extends AbstractPipelineIT {

    @LocalServerPort
    int port;

    @TempDir
    Path dir;

    @Test
    void theServedStreamPlaysInChrome() throws Exception {
        TranscodeEndToEndIT.requireFfmpeg();
        Path sample = dir.resolve("sample.mp4");
        TranscodeEndToEndIT.run(List.of("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "12", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                sample.toString()));

        VideoDTO created = createVideo();
        assertThat(created.getPlaybackUrl()).isNull();
        upload(created.getId(), Files.readAllBytes(sample));
        awaitStatus(created.getId(), Video.VideoStatus.READY, Duration.ofMinutes(3));

        String playbackUrl = http.getForObject("/api/videos/" + created.getId(), VideoDTO.class).getPlaybackUrl();
        assertThat(playbackUrl).isEqualTo("/api/videos/" + created.getId() + "/hls/master.m3u8");
        assertEveryPlaylistUriIsServed(playbackUrl);
        assertChromePlaysIt(created.getId());
    }

    /** The master and the variant playlists answer 200 with the playlist type, and so does every URI they list. */
    private void assertEveryPlaylistUriIsServed(String playbackUrl) {
        URI master = URI.create("http://localhost:" + port + playbackUrl);
        ResponseEntity<String> masterResponse = http.getForEntity(master, String.class);
        assertThat(masterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(masterResponse.getHeaders().getContentType().toString()).isEqualTo("application/vnd.apple.mpegurl");

        List<URI> variants = uris(masterResponse.getBody(), master);
        assertThat(variants).hasSize(2);
        int segments = 0;
        for (URI variant : variants) {
            ResponseEntity<String> variantResponse = http.getForEntity(variant, String.class);
            assertThat(variantResponse.getStatusCode()).as(variant.toString()).isEqualTo(HttpStatus.OK);
            assertThat(variantResponse.getHeaders().getContentType().toString())
                    .isEqualTo("application/vnd.apple.mpegurl");
            for (URI segment : uris(variantResponse.getBody(), variant)) {
                ResponseEntity<byte[]> segmentResponse = http.getForEntity(segment, byte[].class);
                assertThat(segmentResponse.getStatusCode()).as(segment.toString()).isEqualTo(HttpStatus.OK);
                assertThat(segmentResponse.getHeaders().getContentType().toString()).isEqualTo("video/mp2t");
                assertThat(segmentResponse.getHeaders().getCacheControl()).contains("immutable");
                assertThat(segmentResponse.getBody()).isNotEmpty();
                segments++;
            }
        }
        // 12 s at 6 s per segment, in two renditions.
        assertThat(segments).isEqualTo(4);
        assertThat(http.getForEntity(master, String.class).getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    private static List<URI> uris(String playlist, URI base) {
        return playlist.lines().filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(String::trim).map(base::resolve).toList();
    }

    private void assertChromePlaysIt(Long videoId) {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchChrome(playwright);
            if (browser == null) {
                return;
            }
            try (browser) {
                Page page = browser.newPage();
                page.onConsoleMessage(m -> diagnostics.add("console " + m.type() + ": " + m.text()));
                page.onPageError(e -> diagnostics.add("page error: " + e));
                page.onRequestFailed(r -> diagnostics.add("request failed: " + r.url() + " " + r.failure()));
                page.onResponse(r -> {
                    if (r.status() >= 400) {
                        diagnostics.add("HTTP " + r.status() + ": " + r.url());
                    }
                });

                page.navigate("http://localhost:" + port + "/player.html?video=" + videoId);
                try {
                    page.waitForFunction(
                            "() => window.player && (window.player.fatalError || window.player.currentTime > 2)",
                            null, new Page.WaitForFunctionOptions().setTimeout(60_000));
                } catch (PlaywrightException e) {
                    diagnostics.add("timed out waiting for playback: " + e.getMessage());
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> state = (Map<String, Object>) page.evaluate("() => window.player");
                String context = "player " + state + "\n" + String.join("\n", diagnostics);
                assertThat(state).as(context).isNotNull();
                assertThat(state.get("supported")).as("Hls.isSupported()\n" + context).isEqualTo(true);
                assertThat(state.get("fatalError")).as("fatal error\n" + context).isNull();
                assertThat(((Number) state.get("levels")).intValue()).as("levels\n" + context).isEqualTo(2);
                assertThat(((Number) state.get("currentTime")).doubleValue()).as("currentTime\n" + context)
                        .isGreaterThan(2.0);
            }
        }
    }

    /** Launches the installed Google Chrome, or skips the test (fails it when REQUIRE_BROWSER=true) without it. */
    private static Browser launchChrome(Playwright playwright) {
        try {
            return playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel("chrome")
                    .setHeadless(true)
                    // The runner's user namespace policy can break Chrome's sandbox; the page is our own, on localhost.
                    .setArgs(new ArrayList<>(List.of("--no-sandbox", "--autoplay-policy=no-user-gesture-required"))));
        } catch (PlaywrightException e) {
            if ("true".equalsIgnoreCase(System.getenv("REQUIRE_BROWSER"))) {
                throw new AssertionError("REQUIRE_BROWSER=true but Google Chrome could not be started", e);
            }
            assumeTrue(false, "Google Chrome is not available: " + e.getMessage());
            return null;
        }
    }
}
