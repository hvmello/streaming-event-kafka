package com.example.videostream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * FFmpeg transcoding settings.
 *
 * @param enabled         run the {@code video.uploaded} consumer; tests that only need the upload flow turn it off
 * @param ffmpegPath      FFmpeg binary, resolved on the {@code PATH} when it has no directory
 * @param timeout         how long one FFmpeg process may run before it is destroyed and the job fails
 * @param segmentSeconds  HLS segment length, which should match {@code Video.segmentDurationMs}
 * @param concurrency     listener threads and maximum concurrent FFmpeg jobs
 * @param maxPollInterval consumer {@code max.poll.interval.ms}; the paused partition keeps polling, so a job may
 *                        run far longer than this
 * @param lease           how long a transcode claim lives without a heartbeat; the running job renews it every
 *                        third of this, and a claim past it is treated as dead and may be taken over. It must stay
 *                        much larger than the clock skew between workers
 * @param renditions      the HLS renditions to produce, highest first
 */
@ConfigurationProperties("videostream.transcode")
public record TranscodeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ffmpeg") String ffmpegPath,
        @DefaultValue("PT30M") Duration timeout,
        @DefaultValue("6") int segmentSeconds,
        @DefaultValue("1") int concurrency,
        @DefaultValue("PT5M") Duration maxPollInterval,
        @DefaultValue("PT60S") Duration lease,
        List<Rendition> renditions) {

    /**
     * One output rendition.
     *
     * @param name      playlist directory and label, for example {@code 720p}
     * @param height    output height in pixels; the width follows the aspect ratio
     * @param videoKbps H.264 bitrate
     * @param audioKbps AAC bitrate
     */
    public record Rendition(String name, int height, int videoKbps, int audioKbps) {
    }

    public TranscodeProperties {
        if (renditions == null || renditions.isEmpty()) {
            renditions = List.of(
                    new Rendition("720p", 720, 2800, 128),
                    new Rendition("360p", 360, 800, 96));
        }
        renditions = List.copyOf(renditions);
        if (segmentSeconds < 1 || concurrency < 1) {
            throw new IllegalArgumentException("segment-seconds and concurrency must be at least 1");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be a positive duration");
        }
    }
}
