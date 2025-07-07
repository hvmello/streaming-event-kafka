package com.example.videostream.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a user's viewing session.
 * 
 * This entity tracks a user's interaction with a video, including
 * playback position, quality level, and analytics data.
 * It's used for implementing structured concurrency in session management.
 */
@Entity
@Table(name = "viewing_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ViewingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String sessionId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;
    
    @Column(nullable = false)
    private LocalDateTime startedAt;
    
    @Column
    private LocalDateTime endedAt;
    
    @Column(nullable = false)
    private Integer lastPositionMs = 0;
    
    @Column(nullable = false)
    private String currentQualityLevel;
    
    @Column(nullable = false)
    private String clientIpAddress;
    
    @Column(nullable = false)
    private String userAgent;
    
    @Column(nullable = false)
    private boolean active = true;
    
    @ElementCollection
    @CollectionTable(name = "session_quality_switches", joinColumns = @JoinColumn(name = "session_id"))
    @OrderColumn(name = "switch_order")
    private List<QualitySwitch> qualitySwitches = new ArrayList<>();
    
    @ElementCollection
    @CollectionTable(name = "session_buffer_events", joinColumns = @JoinColumn(name = "session_id"))
    @OrderColumn(name = "event_order")
    private List<BufferEvent> bufferEvents = new ArrayList<>();
    
    /**
     * Records a quality level switch during playback.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualitySwitch {
        private LocalDateTime timestamp;
        private String fromQuality;
        private String toQuality;
        private Integer positionMs;
        private String reason;  // "network", "user", "startup"
    }
    
    /**
     * Records a buffering event during playback.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BufferEvent {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer positionMs;
        private Long durationMs;
    }
}