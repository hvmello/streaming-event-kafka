package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties;
import com.example.videostream.config.TranscodeProperties.Rendition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * {@link FfmpegRunner} that starts the FFmpeg binary with {@link ProcessBuilder} and an argument list, never a
 * shell string, so file names and settings cannot inject commands.
 *
 * <p>One process produces every rendition: the input is decoded once and split, then each branch is scaled and
 * encoded into its own variant playlist.
 */
@Slf4j
@Component
public class ProcessFfmpegRunner implements FfmpegRunner {

    private static final int OUTPUT_TAIL_LINES = 40;
    private static final Pattern AUDIO_STREAM = Pattern.compile("Stream #\\d+:\\d+.*: Audio:");

    private final String ffmpegPath;
    private final Duration timeout;
    private final int segmentSeconds;

    public ProcessFfmpegRunner(TranscodeProperties properties) {
        this.ffmpegPath = properties.ffmpegPath();
        this.timeout = properties.timeout();
        this.segmentSeconds = properties.segmentSeconds();
    }

    @Override
    public void transcode(Path input, Path outputDir, List<Rendition> renditions)
            throws IOException, InterruptedException {
        for (Rendition rendition : renditions) {
            Files.createDirectories(outputDir.resolve(rendition.name()));
        }
        boolean hasAudio = hasAudio(input);
        if (!hasAudio) {
            log.info("{} has no audio stream; transcoding video only", input.getFileName());
        }
        List<String> command = command(input, outputDir, renditions, hasAudio);
        log.info("Starting FFmpeg for {} with {} renditions", input.getFileName(), renditions.size());
        Result result = execute(command);
        if (result.exit() != 0) {
            throw new IOException("FFmpeg exited with " + result.exit() + ":\n" + result.output());
        }
    }

    /**
     * Whether the input has an audio stream, read from the stream list FFmpeg prints for an input with no output
     * (it exits non-zero, which is expected here). The drainer checks every line as it arrives, so the answer does
     * not depend on how many lines the output tail keeps.
     */
    private boolean hasAudio(Path input) throws IOException, InterruptedException {
        Result probe = execute(List.of(ffmpegPath, "-hide_banner", "-nostdin", "-i", input.toString()));
        return probe.audioSeen();
    }

    private record Result(int exit, String output, boolean audioSeen) {
    }

    /** Runs the command to completion, or destroys it after the timeout or on interruption. */
    private Result execute(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        // FFmpeg writes progress to stderr; an undrained pipe would block it once the buffer fills.
        Deque<String> tail = new ArrayDeque<>();
        AtomicBoolean audioSeen = new AtomicBoolean();
        Thread drainer = Thread.ofPlatform().daemon().name("ffmpeg-output")
                .start(() -> drain(process, tail, audioSeen));
        boolean finished = false;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!finished) {
                // Also reached on interruption. Children first: a wrapper script would otherwise keep the pipe open.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
        drainer.join(5_000);
        if (!finished) {
            throw new IOException("FFmpeg did not finish within " + timeout + " and was destroyed");
        }
        return new Result(process.exitValue(), String.join("\n", snapshot(tail)), audioSeen.get());
    }

    /**
     * The FFmpeg arguments for the given renditions. Without audio, no audio is mapped or encoded, because
     * FFmpeg refuses to start when a map matches no stream. Visible for tests.
     */
    List<String> command(Path input, Path outputDir, List<Rendition> renditions, boolean hasAudio) {
        int count = renditions.size();
        List<String> args = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-nostdin", "-y",
                "-i", input.toString()));

        StringBuilder filter = new StringBuilder("[0:v]split=").append(count);
        for (int i = 0; i < count; i++) {
            filter.append("[s").append(i).append(']');
        }
        for (int i = 0; i < count; i++) {
            // -2 keeps the width even, which H.264 with yuv420p requires.
            filter.append(";[s").append(i).append("]scale=-2:").append(renditions.get(i).height())
                    .append("[v").append(i).append(']');
        }
        args.add("-filter_complex");
        args.add(filter.toString());

        StringBuilder streamMap = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Rendition r = renditions.get(i);
            args.addAll(List.of("-map", "[v" + i + "]",
                    "-c:v:" + i, "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                    "-b:v:" + i, r.videoKbps() + "k"));
            if (hasAudio) {
                args.addAll(List.of("-map", "a:0", "-c:a:" + i, "aac", "-b:a:" + i, r.audioKbps() + "k"));
            }
            if (i > 0) {
                streamMap.append(' ');
            }
            streamMap.append("v:").append(i);
            if (hasAudio) {
                streamMap.append(",a:").append(i);
            }
            streamMap.append(",name:").append(r.name());
        }

        args.addAll(List.of(
                // Keyframes on segment boundaries, so every rendition cuts at the same times.
                "-force_key_frames", "expr:gte(t,n_forced*" + segmentSeconds + ")",
                "-sc_threshold", "0",
                "-f", "hls",
                "-hls_time", String.valueOf(segmentSeconds),
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", outputDir.resolve("%v").resolve("seg_%03d.ts").toString(),
                "-master_pl_name", MASTER_PLAYLIST,
                "-var_stream_map", streamMap.toString(),
                outputDir.resolve("%v").resolve("index.m3u8").toString()));
        return args;
    }

    private void drain(Process process, Deque<String> tail, AtomicBoolean audioSeen) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ffmpeg: {}", line);
                if (AUDIO_STREAM.matcher(line).find()) {
                    audioSeen.set(true);
                }
                synchronized (tail) {
                    if (tail.size() == OUTPUT_TAIL_LINES) {
                        tail.removeFirst();
                    }
                    tail.addLast(line);
                }
            }
        } catch (IOException e) {
            log.debug("Stopped reading FFmpeg output", e);
        }
    }

    private static List<String> snapshot(Deque<String> tail) {
        synchronized (tail) {
            return new ArrayList<>(tail);
        }
    }
}
