package com.example.videostream.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing a video in the catalog.
 * 
 * This entity stores metadata about videos in the system, including
 * information about available quality levels and segments.
 */
@Entity
@Table(name = "videos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    @EqualsAndHashCode.Include
    private String contentId;  // Unique identifier for content storage

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private Integer durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoStatus status;

    @ElementCollection
    @CollectionTable(name = "video_quality_levels", joinColumns = @JoinColumn(name = "video_id"))
    @Column(name = "quality_level")
    private Set<String> availableQualityLevels = new HashSet<>();

    @Column(nullable = false)
    private Integer segmentDurationMs = 6000;  // Default 6-second segments

    @Column(nullable = false)
    private Integer segmentCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User uploader;

    @Column(nullable = false)
    private Long viewCount = 0L;

    /**
     * Status of a video in the system.
     */
    public enum VideoStatus {
        UPLOADING,
        PROCESSING,
        TRANSCODING,
        READY,
        ERROR,
        DELETED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Video video = (Video) o;

        // First try to compare by contentId (business key)
        if (contentId != null && video.contentId != null) {
            return contentId.equals(video.contentId);
        }

        // Fall back to id comparison if contentId is null
        return id != null && id.equals(video.id);
    }

    @Override
    public int hashCode() {
        // Use contentId for hash code if available, otherwise use id
        return contentId != null ? contentId.hashCode() : (id != null ? id.hashCode() : 0);
    }
}
