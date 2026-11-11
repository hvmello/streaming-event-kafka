package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessFfmpegRunnerTest {

    private static final List<Rendition> TWO = List.of(
            new Rendition("720p", 720, 2800, 128),
            new Rendition("360p", 360, 800, 96));

    @TempDir
    Path dir;

    private ProcessFfmpegRunner runner(String binary, Duration timeout) {
        return new ProcessFfmpegRunner(new TranscodeProperties(true, binary, timeout, 6, 1, Duration.ofMinutes(5), null));
    }

    @Test
    void buildsOneProcessThatSplitsAndEncodesTwoRenditions() {
        Path input = dir.resolve("input");
        Path out = dir.resolve("out");

        List<String> command = runner("ffmpeg", Duration.ofMinutes(1)).command(input, out, TWO, true);

        assertThat(command.get(0)).isEqualTo("ffmpeg");
        assertThat(argAfter(command, "-i")).isEqualTo(input.toString());
        assertThat(argAfter(command, "-filter_complex"))
                .isEqualTo("[0:v]split=2[s0][s1];[s0]scale=-2:720[v0];[s1]scale=-2:360[v1]");
        assertThat(command).containsSubsequence("-map", "[v0]", "-c:v:0", "libx264");
        assertThat(command).containsSubsequence("-map", "[v1]", "-c:v:1", "libx264");
        assertThat(argAfter(command, "-b:v:0")).isEqualTo("2800k");
        assertThat(argAfter(command, "-b:v:1")).isEqualTo("800k");
        assertThat(argAfter(command, "-b:a:0")).isEqualTo("128k");
        assertThat(argAfter(command, "-b:a:1")).isEqualTo("96k");
        assertThat(argAfter(command, "-f")).isEqualTo("hls");
        assertThat(argAfter(command, "-hls_time")).isEqualTo("6");
        assertThat(argAfter(command, "-hls_playlist_type")).isEqualTo("vod");
        assertThat(argAfter(command, "-master_pl_name")).isEqualTo("master.m3u8");
        assertThat(argAfter(command, "-var_stream_map"))
                .isEqualTo("v:0,a:0,name:720p v:1,a:1,name:360p");
        assertThat(argAfter(command, "-hls_segment_filename")).endsWith("seg_%03d.ts").contains("%v");
        assertThat(command.get(command.size() - 1)).endsWith("index.m3u8").contains("%v");
    }

    @Test
    void aVideoWithoutAudioMapsNoAudioStream() {
        List<String> command = runner("ffmpeg", Duration.ofMinutes(1))
                .command(dir.resolve("input"), dir.resolve("out"), TWO, false);

        assertThat(command).doesNotContain("a:0", "-c:a:0", "-b:a:0", "-c:a:1", "-b:a:1");
        assertThat(argAfter(command, "-var_stream_map")).isEqualTo("v:0,name:720p v:1,name:360p");
    }

    @Test
    void anInputWithAnAudioStreamIsDetectedFromTheProbeOutput() throws IOException, InterruptedException {
        Path script = scriptPrinting(true, 0);

        runner(script.toString(), Duration.ofSeconds(30)).transcode(dir.resolve("input"), dir.resolve("out"), TWO);

        List<String> calls = Files.readAllLines(dir.resolve("calls.txt"));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).contains("-map a:0");
    }

    @Test
    void anAudioStreamFollowedByManyMoreLinesIsStillDetected() throws IOException, InterruptedException {
        Path script = scriptPrinting(true, 100);

        runner(script.toString(), Duration.ofSeconds(30)).transcode(dir.resolve("input"), dir.resolve("out"), TWO);

        List<String> calls = Files.readAllLines(dir.resolve("calls.txt"));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).contains("-map a:0");
    }

    @Test
    void anInputWithoutAnAudioStreamIsTranscodedWithoutAudio() throws IOException, InterruptedException {
        Path script = scriptPrinting(false, 0);

        runner(script.toString(), Duration.ofSeconds(30)).transcode(dir.resolve("input"), dir.resolve("out"), TWO);

        List<String> calls = Files.readAllLines(dir.resolve("calls.txt"));
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).doesNotContain("a:0").contains("v:0,name:720p");
    }

    @Test
    void passesFileNamesAsSingleArgumentsNeverThroughAShell() {
        Path input = dir.resolve("a b; rm -rf x");

        List<String> command = runner("ffmpeg", Duration.ofMinutes(1)).command(input, dir.resolve("out"), TWO, true);

        assertThat(command).contains(input.toString());
    }

    @Test
    void aProcessThatOutlivesTheTimeoutIsDestroyed() throws IOException {
        Path script = sleepingScript();
        long start = System.nanoTime();

        assertThatThrownBy(() -> runner(script.toString(), Duration.ofMillis(500))
                .transcode(dir.resolve("input"), dir.resolve("out"), TWO))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish")
                .hasMessageContaining("destroyed");

        // The stand-in sleeps for 60 s; returning quickly means the process was killed, not awaited.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void aNonZeroExitFailsWithTheOutputTail() throws IOException {
        Path script = failingScript();

        assertThatThrownBy(() -> runner(script.toString(), Duration.ofSeconds(30))
                .transcode(dir.resolve("input"), dir.resolve("out"), TWO))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with 3")
                .hasMessageContaining("bad input");
    }

    private static String argAfter(List<String> command, String flag) {
        return command.get(command.indexOf(flag) + 1);
    }

    private Path sleepingScript() throws IOException {
        if (windows()) {
            Path script = dir.resolve("fake-ffmpeg.cmd");
            Files.writeString(script, "@echo off\r\nping -n 60 127.0.0.1 > nul\r\n");
            return script;
        }
        Path script = dir.resolve("fake-ffmpeg.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 60\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /** A stand-in that prints a stream list, then trailing metadata lines, and appends its arguments to calls.txt. POSIX only. */
    private Path scriptPrinting(boolean withAudio, int trailingLines) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse(windows());
        Path script = dir.resolve("print-ffmpeg.sh");
        String audio = withAudio ? "echo '  Stream #0:1(und): Audio: aac'\n" : "";
        Files.writeString(script, "#!/bin/sh\necho \"$@\" >> '" + dir.resolve("calls.txt") + "'\n"
                + "echo '  Stream #0:0: Video: h264'\n" + audio
                + "i=0; while [ $i -lt " + trailingLines + " ]; do echo \"    Metadata: line $i\"; i=$((i+1)); done\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private Path failingScript() throws IOException {
        if (windows()) {
            Path script = dir.resolve("fail-ffmpeg.cmd");
            Files.writeString(script, "@echo off\r\necho bad input\r\nexit /b 3\r\n");
            return script;
        }
        Path script = dir.resolve("fail-ffmpeg.sh");
        Files.writeString(script, "#!/bin/sh\necho bad input\nexit 3\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
