package com.example.videostream.service.catalog;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.User;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.UserRepository;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.streaming.transcode.TranscodingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Service for managing the video catalog.
 * 
 * This service provides methods for creating, retrieving, updating, and
 * deleting videos in the catalog. It also handles video uploads and
 * initiates the transcoding process.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final TranscodingService transcodingService;
    
    /**
     * Get a paginated list of videos.
     * 
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs
     */
    @Transactional(readOnly = true)
    public Page<VideoDTO> getVideos(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("uploadedAt").descending());
        return videoRepository.findByStatus(Video.VideoStatus.READY, pageable)
                .map(VideoDTO::fromEntity);
    }
    
    /**
     * Search for videos by title or description.
     * 
     * @param query The search query
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs matching the search query
     */
    @Transactional(readOnly = true)
    public Page<VideoDTO> searchVideos(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return videoRepository.searchVideos(query, pageable)
                .map(VideoDTO::fromEntity);
    }
    
    /**
     * Get popular videos based on view count.
     * 
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of popular video DTOs
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "popularVideos", key = "#page + '-' + #size")
    public Page<VideoDTO> getPopularVideos(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return videoRepository.findPopularVideos(pageable)
                .map(VideoDTO::fromEntity);
    }
    
    /**
     * Get videos uploaded by a specific user.
     * 
     * @param userId The ID of the user
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs uploaded by the user
     */
    @Transactional(readOnly = true)
    public Page<VideoDTO> getUserVideos(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("uploadedAt").descending());
        return videoRepository.findByUploaderId(userId, pageable)
                .map(VideoDTO::fromEntity);
    }
    
    /**
     * Get a video by ID.
     * 
     * @param videoId The ID of the video
     * @return The video DTO
     * @throws ResponseStatusException if the video is not found
     */
    @Transactional(readOnly = true)
    public VideoDTO getVideo(Long videoId) {
        return videoRepository.findById(videoId)
                .map(VideoDTO::fromEntity)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found"));
    }
    
    /**
     * Create a new video entry in the catalog.
     * 
     * @param title The title of the video
     * @param description The description of the video
     * @param durationSeconds The duration of the video in seconds
     * @param userId The ID of the uploader
     * @return The created video DTO
     */
    @Transactional
    public VideoDTO createVideo(String title, String description, Integer durationSeconds, Long userId) {
        User uploader = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        
        Video video = new Video();
        video.setTitle(title);
        video.setDescription(description);
        video.setContentId(UUID.randomUUID().toString());
        video.setUploadedAt(LocalDateTime.now());
        video.setDurationSeconds(durationSeconds);
        video.setStatus(Video.VideoStatus.UPLOADING);
        video.setUploader(uploader);
        video.setSegmentDurationMs(6000); // 6 seconds per segment
        video.setSegmentCount(durationSeconds * 1000 / 6000); // Calculate number of segments
        video.setViewCount(0L);
        
        Video savedVideo = videoRepository.save(video);
        log.info("Created new video: {}", savedVideo.getId());
        
        return VideoDTO.fromEntity(savedVideo);
    }
    
    /**
     * Upload video content and start the transcoding process.
     * 
     * @param videoId The ID of the video
     * @param content The video content
     * @return A CompletableFuture that completes when the upload is processed
     */
    @Transactional
    public CompletableFuture<Void> uploadVideoContent(Long videoId, byte[] content) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found"));
        
        log.info("Uploading content for video: {}, size: {} bytes", videoId, content.length);
        
        // Submit the video for transcoding
        return transcodingService.submitForTranscoding(video.getContentId(), content);
    }
    
    /**
     * Update a video's metadata.
     * 
     * @param videoId The ID of the video
     * @param title The new title (or null to keep the current value)
     * @param description The new description (or null to keep the current value)
     * @return The updated video DTO
     */
    @Transactional
    public VideoDTO updateVideo(Long videoId, String title, String description) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found"));
        
        if (title != null) {
            video.setTitle(title);
        }
        
        if (description != null) {
            video.setDescription(description);
        }
        
        Video updatedVideo = videoRepository.save(video);
        log.info("Updated video: {}", videoId);
        
        return VideoDTO.fromEntity(updatedVideo);
    }
    
    /**
     * Delete a video from the catalog.
     * 
     * @param videoId The ID of the video
     */
    @Transactional
    public void deleteVideo(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found"));
        
        // Mark the video as deleted (soft delete)
        video.setStatus(Video.VideoStatus.DELETED);
        videoRepository.save(video);
        
        log.info("Deleted video: {}", videoId);
        
        // In a real implementation, we might schedule a background task to
        // clean up the actual video files after some time
    }
}