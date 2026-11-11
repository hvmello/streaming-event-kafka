package com.example.videostream.model.event;

import java.time.Instant;

/**
 * Claim-check reference published to {@code video.uploaded}: it points at the raw object and never carries
 * video bytes.
 */
public record VideoUploadedEvent(
        Long videoId,
        String contentId,
        String bucket,
        String key,
        long sizeBytes,
        String contentType,
        Instant uploadedAt) {
}
