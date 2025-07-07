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
    private UserDTO uploader;
    
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
                .uploader(video.getUploader() != null ? UserDTO.fromEntity(video.getUploader()) : null)
                .build();
    }
}