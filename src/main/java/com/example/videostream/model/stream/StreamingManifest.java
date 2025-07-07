package com.example.videostream.model.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a streaming manifest for adaptive bitrate streaming.
 * 
 * The manifest contains information about available quality levels, segments,
 * and other metadata needed by the client to perform adaptive streaming.
 * This is similar to an HLS playlist or DASH MPD.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamingManifest {
    
    private String videoId;
    private String title;
    private int durationSeconds;
    private int segmentDurationMs;
    private int segmentCount;
    private List<QualityLevel> availableQualities;
    private String baseUrl;
    
    /**
     * Represents a quality level available for streaming.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityLevel {
        private String name;          // e.g., "1080p", "720p"
        private int width;
        private int height;
        private int bitrate;          // in kbps
        private String codecs;        // e.g., "avc1.640028,mp4a.40.2"
        private List<SegmentInfo> segments;
        
        /**
         * Add a segment to this quality level.
         * 
         * @param segment The segment to add
         */
        public void addSegment(SegmentInfo segment) {
            if (segments == null) {
                segments = new ArrayList<>();
            }
            segments.add(segment);
        }
    }
    
    /**
     * Information about a specific segment in the manifest.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SegmentInfo {
        private int segmentNumber;
        private String uri;
        private int durationMs;
        private boolean isKeyframe;
    }
    
    /**
     * Add a quality level to the manifest.
     * 
     * @param qualityLevel The quality level to add
     */
    public void addQualityLevel(QualityLevel qualityLevel) {
        if (availableQualities == null) {
            availableQualities = new ArrayList<>();
        }
        availableQualities.add(qualityLevel);
    }
    
    /**
     * Generate a simple M3U8 playlist for HLS streaming.
     * 
     * @param qualityLevel The quality level to generate the playlist for
     * @return An M3U8 playlist as a string
     */
    public String generateM3U8Playlist(String qualityLevel) {
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:3\n");
        playlist.append("#EXT-X-TARGETDURATION:" + (segmentDurationMs / 1000) + "\n");
        playlist.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        
        QualityLevel quality = availableQualities.stream()
                .filter(q -> q.getName().equals(qualityLevel))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Quality level not found: " + qualityLevel));
        
        for (SegmentInfo segment : quality.getSegments()) {
            playlist.append("#EXTINF:" + (segment.getDurationMs() / 1000.0) + ",\n");
            playlist.append(segment.getUri() + "\n");
        }
        
        playlist.append("#EXT-X-ENDLIST\n");
        return playlist.toString();
    }
}