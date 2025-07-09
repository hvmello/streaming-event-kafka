package com.example.videostream.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Objects;

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
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ViewingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true)
    @EqualsAndHashCode.Include
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
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class QualitySwitch {
        @EqualsAndHashCode.Include
        private LocalDateTime timestamp;
        private String fromQuality;
        private String toQuality;
        @EqualsAndHashCode.Include
        private Integer positionMs;
        private String reason;  // "network", "user", "startup"

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            QualitySwitch that = (QualitySwitch) o;

            if (timestamp == null) {
                if (that.timestamp != null) return false;
            } else if (!timestamp.equals(that.timestamp)) return false;

            if (positionMs == null) {
                return that.positionMs == null;
            } else return positionMs.equals(that.positionMs);
        }

        @Override
        public int hashCode() {
            int result = timestamp != null ? timestamp.hashCode() : 0;
            result = 31 * result + (positionMs != null ? positionMs.hashCode() : 0);
            return result;
        }
    }

    /**
     * Records a buffering event during playback.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class BufferEvent {
        @EqualsAndHashCode.Include
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        @EqualsAndHashCode.Include
        private Integer positionMs;
        private Long durationMs;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            BufferEvent that = (BufferEvent) o;

            if (startTime == null) {
                if (that.startTime != null) return false;
            } else if (!startTime.equals(that.startTime)) return false;

            if (positionMs == null) {
                return that.positionMs == null;
            } else return positionMs.equals(that.positionMs);
        }

        @Override
        public int hashCode() {
            int result = startTime != null ? startTime.hashCode() : 0;
            result = 31 * result + (positionMs != null ? positionMs.hashCode() : 0);
            return result;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ViewingSession session = (ViewingSession) o;

        // First try to compare by sessionId (business key)
        if (sessionId != null && session.sessionId != null) {
            return sessionId.equals(session.sessionId);
        }

        // Fall back to id comparison if sessionId is null
        return id != null && id.equals(session.id);
    }

    @Override
    public int hashCode() {
        // Use sessionId for hash code if available, otherwise use id
        return sessionId != null ? sessionId.hashCode() : (id != null ? id.hashCode() : 0);
    }
}
