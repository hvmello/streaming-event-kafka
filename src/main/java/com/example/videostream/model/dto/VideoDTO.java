package com.example.videostream.model.dto;

import com.example.videostream.model.entity.Video;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Data Transfer Object for video metadata.
 * 
 * This DTO is used for API responses and contains the essential
 * information about a video without exposing internal implementation details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoDTO {
    
    private Long id;
    private String title;
    private String description;
    private String contentId;
    private LocalDateTime uploadedAt;
    private Integer durationSeconds;
    private Video.VideoStatus status;
    private Set<String> availableQualityLevels;
    private Integer segmentDurationMs;
    private Integer segmentCount;
    private Long viewCount;

    /** Path of the HLS master playlist; {@code null} until the video is {@code READY}. */
    private String playbackUrl;
    
    /**
     * Convert a Video entity to a VideoDTO.
     * 
     * @param video The Video entity to convert
     * @return A VideoDTO with the video's data
     */
    public static VideoDTO fromEntity(Video video) {
        return VideoDTO.builder()
                .id(video.getId())
                .title(video.getTitle())
                .description(video.getDescription())
                .contentId(video.getContentId())
                .uploadedAt(video.getUploadedAt())
                .durationSeconds(video.getDurationSeconds())
                .status(video.getStatus())
                .availableQualityLevels(video.getAvailableQualityLevels())
                .segmentDurationMs(video.getSegmentDurationMs())
                .segmentCount(video.getSegmentCount())
                .viewCount(video.getViewCount())
                .playbackUrl(video.getStatus() == Video.VideoStatus.READY
                        ? "/api/videos/" + video.getId() + "/hls/master.m3u8" : null)
                .build();
    }
}