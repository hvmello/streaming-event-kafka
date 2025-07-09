package com.example.videostream.model.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * Represents a segment of a video for streaming.
 * 
 * Video segments are the fundamental unit of streaming in adaptive bitrate systems.
 * Each segment contains a small portion of the video (typically 2-10 seconds) at a
 * specific quality level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class VideoSegment {

    @EqualsAndHashCode.Include
    private String videoId;
    @EqualsAndHashCode.Include
    private int segmentNumber;
    @EqualsAndHashCode.Include
    private String qualityLevel;  // e.g., "1080p", "720p", "480p", "360p"
    private int bitrate;          // in kbps
    private int durationMs;
    private byte[] data;
    private String contentType;   // e.g., "video/mp4"
    private long timestamp;

    // Metadata for caching decisions
    private int popularityScore;
    private long lastAccessed;
    private int accessCount;

    /**
     * Calculate the size of this segment in bytes.
     * 
     * @return The size in bytes
     */
    public int getSizeInBytes() {
        return data != null ? data.length : 0;
    }

    /**
     * Check if this segment is a keyframe (I-frame) that can be used as a starting point.
     * 
     * @return True if this is a keyframe segment
     */
    public boolean isKeyframe() {
        // In a real implementation, this would be determined from the video data
        // For this example, we'll assume every 10th segment is a keyframe
        return segmentNumber % 10 == 0;
    }

    /**
     * Get the segment path for HTTP requests.
     * 
     * @return The path to this segment
     */
    public String getPath() {
        return String.format("/videos/%s/%s/segment%d.mp4", 
                videoId, qualityLevel, segmentNumber);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VideoSegment segment = (VideoSegment) o;

        if (segmentNumber != segment.segmentNumber) return false;
        if (videoId == null) {
            if (segment.videoId != null) return false;
        } else if (!videoId.equals(segment.videoId)) return false;
        if (qualityLevel == null) {
            return segment.qualityLevel == null;
        } else return qualityLevel.equals(segment.qualityLevel);
    }

    @Override
    public int hashCode() {
        int result = videoId != null ? videoId.hashCode() : 0;
        result = 31 * result + segmentNumber;
        result = 31 * result + (qualityLevel != null ? qualityLevel.hashCode() : 0);
        return result;
    }
}
