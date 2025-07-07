package com.example.videostream.service.streaming.adaptive;

import com.example.videostream.model.entity.Video;
import com.example.videostream.model.stream.StreamingManifest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Component for generating streaming manifests for adaptive bitrate streaming.
 * 
 * This component creates manifests (similar to HLS playlists or DASH MPDs)
 * that clients use to request the appropriate video segments based on
 * their network conditions.
 */
@Component
public class ManifestGenerator {

    // Standard quality levels for adaptive streaming
    private static final List<String> STANDARD_QUALITIES = Arrays.asList(
            "1080p", "720p", "480p", "360p", "240p"
    );
    
    /**
     * Generate a streaming manifest for a video.
     * 
     * @param video The video entity
     * @return A streaming manifest
     */
    public StreamingManifest generateManifest(Video video) {
        StreamingManifest manifest = StreamingManifest.builder()
                .videoId(video.getContentId())
                .title(video.getTitle())
                .durationSeconds(video.getDurationSeconds())
                .segmentDurationMs(video.getSegmentDurationMs())
                .segmentCount(video.getSegmentCount())
                .baseUrl("/api/videos/" + video.getId() + "/segments")
                .build();
        
        // Add quality levels based on what's available for this video
        for (String quality : video.getAvailableQualityLevels()) {
            StreamingManifest.QualityLevel qualityLevel = createQualityLevel(quality, video);
            manifest.addQualityLevel(qualityLevel);
        }
        
        // If no quality levels are available yet (e.g., video is still processing),
        // add placeholder quality levels
        if (manifest.getAvailableQualities() == null || manifest.getAvailableQualities().isEmpty()) {
            for (String quality : STANDARD_QUALITIES) {
                if (isQualityAvailableForVideo(quality, video)) {
                    StreamingManifest.QualityLevel qualityLevel = createQualityLevel(quality, video);
                    manifest.addQualityLevel(qualityLevel);
                }
            }
        }
        
        return manifest;
    }
    
    /**
     * Create a quality level for the manifest.
     * 
     * @param quality The quality level name
     * @param video The video entity
     * @return A quality level
     */
    private StreamingManifest.QualityLevel createQualityLevel(String quality, Video video) {
        StreamingManifest.QualityLevel qualityLevel = new StreamingManifest.QualityLevel();
        qualityLevel.setName(quality);
        
        // Set resolution based on quality name
        switch (quality) {
            case "1080p" -> {
                qualityLevel.setWidth(1920);
                qualityLevel.setHeight(1080);
                qualityLevel.setBitrate(8000);
            }
            case "720p" -> {
                qualityLevel.setWidth(1280);
                qualityLevel.setHeight(720);
                qualityLevel.setBitrate(4500);
            }
            case "480p" -> {
                qualityLevel.setWidth(854);
                qualityLevel.setHeight(480);
                qualityLevel.setBitrate(2500);
            }
            case "360p" -> {
                qualityLevel.setWidth(640);
                qualityLevel.setHeight(360);
                qualityLevel.setBitrate(1500);
            }
            case "240p" -> {
                qualityLevel.setWidth(426);
                qualityLevel.setHeight(240);
                qualityLevel.setBitrate(800);
            }
            default -> {
                qualityLevel.setWidth(640);
                qualityLevel.setHeight(360);
                qualityLevel.setBitrate(1500);
            }
        }
        
        qualityLevel.setCodecs("avc1.640028,mp4a.40.2");
        
        // Generate segment info for this quality level
        for (int i = 0; i < video.getSegmentCount(); i++) {
            StreamingManifest.SegmentInfo segment = new StreamingManifest.SegmentInfo();
            segment.setSegmentNumber(i);
            segment.setUri("/api/videos/" + video.getId() + "/segments/" + i + "/" + quality);
            segment.setDurationMs(video.getSegmentDurationMs());
            segment.setKeyframe(i % 10 == 0); // Assume every 10th segment is a keyframe
            qualityLevel.addSegment(segment);
        }
        
        return qualityLevel;
    }
    
    /**
     * Check if a quality level is available for a video.
     * 
     * @param quality The quality level name
     * @param video The video entity
     * @return True if the quality is available
     */
    private boolean isQualityAvailableForVideo(String quality, Video video) {
        // In a real implementation, this would check if the transcoded version exists
        // For this example, we'll assume all standard qualities are available for READY videos
        return video.getStatus() == Video.VideoStatus.READY;
    }
}