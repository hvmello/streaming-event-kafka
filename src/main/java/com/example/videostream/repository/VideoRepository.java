package com.example.videostream.repository;

import com.example.videostream.model.entity.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Of the given object keys, the ones that a video row references. One indexed query per batch.
     *
     * @param keys The object keys to look for
     * @return the subset of {@code keys} that is referenced
     */
    @Query("SELECT v.rawObjectKey FROM Video v WHERE v.rawObjectKey IN :keys")
    Set<String> findReferencedKeys(Collection<String> keys);

    /**
     * Record a committed upload and move the video to PROCESSING, only if it is still UPLOADING.
     * The status condition makes the transition atomic, so two concurrent uploads cannot both win.
     *
     * @return the number of rows updated: 1 on success, 0 if the video is missing or not UPLOADING
     */
    @Modifying
    @Query("UPDATE Video v SET v.rawObjectKey = :key, v.rawSizeBytes = :size, v.status = 'PROCESSING' " +
           "WHERE v.id = :videoId AND v.status = 'UPLOADING'")
    int markProcessing(Long videoId, String key, long size);

    /**
     * Claim a video for transcoding, only if it is still PROCESSING. Zero rows means the event is stale or a
     * duplicate.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE Video v SET v.status = 'TRANSCODING' WHERE v.id = :videoId AND v.status = 'PROCESSING'")
    int markTranscoding(Long videoId);

    /**
     * Publish the result of a transcode, only if the video is still TRANSCODING.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE Video v SET v.status = 'READY', v.hlsManifestKey = :manifestKey " +
           "WHERE v.id = :videoId AND v.status = 'TRANSCODING'")
    int markReady(Long videoId, String manifestKey);

    /**
     * Fail a transcode, only if the video is still TRANSCODING.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE Video v SET v.status = 'ERROR' WHERE v.id = :videoId AND v.status = 'TRANSCODING'")
    int markTranscodeFailed(Long videoId);

    /**
     * Give a transcode back, only if the video is still TRANSCODING: the worker was interrupted (shutdown), the
     * transcode did not fail, and the redelivered event must be able to claim it again.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE Video v SET v.status = 'PROCESSING' WHERE v.id = :videoId AND v.status = 'TRANSCODING'")
    int markTranscodeInterrupted(Long videoId);

    /**
     * Find videos that need transcoding.
     * 
     * @return A list of videos with PROCESSING status
     */
    List<Video> findByStatusOrderByUploadedAtAsc(Video.VideoStatus status);
}