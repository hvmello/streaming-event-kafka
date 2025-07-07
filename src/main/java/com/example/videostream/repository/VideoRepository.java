package com.example.videostream.repository;

import com.example.videostream.model.entity.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Video entity operations.
 */
@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    
    /**
     * Find a video by its content ID.
     * 
     * @param contentId The content ID to search for
     * @return An Optional containing the video if found
     */
    Optional<Video> findByContentId(String contentId);
    
    /**
     * Find videos by status.
     * 
     * @param status The status to filter by
     * @param pageable Pagination information
     * @return A page of videos with the specified status
     */
    Page<Video> findByStatus(Video.VideoStatus status, Pageable pageable);
    
    /**
     * Find videos uploaded by a specific user.
     * 
     * @param userId The ID of the uploader
     * @param pageable Pagination information
     * @return A page of videos uploaded by the user
     */
    Page<Video> findByUploaderId(Long userId, Pageable pageable);
    
    /**
     * Search for videos by title or description.
     * 
     * @param query The search query
     * @param pageable Pagination information
     * @return A page of videos matching the search query
     */
    @Query("SELECT v FROM Video v WHERE v.status = 'READY' AND " +
           "(LOWER(v.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(v.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Video> searchVideos(String query, Pageable pageable);
    
    /**
     * Find popular videos based on view count.
     * 
     * @param pageable Pagination information
     * @return A page of videos ordered by view count
     */
    @Query("SELECT v FROM Video v WHERE v.status = 'READY' ORDER BY v.viewCount DESC")
    Page<Video> findPopularVideos(Pageable pageable);
    
    /**
     * Increment the view count of a video.
     * 
     * @param videoId The ID of the video
     * @return The number of rows affected
     */
    @Modifying
    @Transactional
    @Query("UPDATE Video v SET v.viewCount = v.viewCount + 1 WHERE v.id = :videoId")
    int incrementViewCount(Long videoId);
    
    /**
     * Find videos that need transcoding.
     * 
     * @return A list of videos with PROCESSING status
     */
    List<Video> findByStatusOrderByUploadedAtAsc(Video.VideoStatus status);
}