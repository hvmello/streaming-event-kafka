package com.example.videostream.repository;

import com.example.videostream.model.entity.ViewingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ViewingSession entity operations.
 */
@Repository
public interface ViewingSessionRepository extends JpaRepository<ViewingSession, Long> {
    
    /**
     * Find a viewing session by its session ID.
     * 
     * @param sessionId The session ID to search for
     * @return An Optional containing the session if found
     */
    Optional<ViewingSession> findBySessionId(String sessionId);
    
    /**
     * Find active viewing sessions for a specific user.
     * 
     * @param userId The ID of the user
     * @return A list of active viewing sessions for the user
     */
    List<ViewingSession> findByUserIdAndActiveTrue(Long userId);
    
    /**
     * Find active viewing sessions for a specific video.
     * 
     * @param videoId The ID of the video
     * @return A list of active viewing sessions for the video
     */
    List<ViewingSession> findByVideoIdAndActiveTrue(Long videoId);
    
    /**
     * Find viewing sessions that have been inactive for a certain period.
     * 
     * @param cutoffTime The cutoff time for inactivity
     * @return A list of inactive sessions
     */
    @Query("SELECT vs FROM ViewingSession vs WHERE vs.active = true AND vs.endedAt IS NULL AND vs.startedAt < :cutoffTime")
    List<ViewingSession> findInactiveSessions(LocalDateTime cutoffTime);
    
    /**
     * Count concurrent viewers for a specific video.
     * 
     * @param videoId The ID of the video
     * @return The number of active viewing sessions for the video
     */
    long countByVideoIdAndActiveTrue(Long videoId);
    
    /**
     * Find recent viewing sessions for analytics.
     * 
     * @param startTime The start time for the query
     * @param endTime The end time for the query
     * @return A list of viewing sessions within the time range
     */
    List<ViewingSession> findByStartedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * Find viewing sessions by client IP address.
     * 
     * @param ipAddress The client IP address
     * @return A list of viewing sessions from the IP address
     */
    List<ViewingSession> findByClientIpAddress(String ipAddress);
}