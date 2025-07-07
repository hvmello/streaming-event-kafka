package com.example.videostream.controller;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.stream.StreamingManifest;
import com.example.videostream.service.catalog.CatalogService;
import com.example.videostream.service.streaming.adaptive.AdaptiveStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for video catalog operations.
 * 
 * This controller provides REST endpoints for managing videos in the catalog,
 * including creating, retrieving, updating, and deleting videos.
 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final CatalogService catalogService;
    private final AdaptiveStreamingService streamingService;
    
    /**
     * Get a paginated list of videos.
     * 
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs
     */
    @GetMapping
    public ResponseEntity<Page<VideoDTO>> getVideos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        return ResponseEntity.ok(catalogService.getVideos(page, size));
    }
    
    /**
     * Search for videos by title or description.
     * 
     * @param query The search query
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs matching the search query
     */
    @GetMapping("/search")
    public ResponseEntity<Page<VideoDTO>> searchVideos(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        return ResponseEntity.ok(catalogService.searchVideos(query, page, size));
    }
    
    /**
     * Get popular videos based on view count.
     * 
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of popular video DTOs
     */
    @GetMapping("/popular")
    public ResponseEntity<Page<VideoDTO>> getPopularVideos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        return ResponseEntity.ok(catalogService.getPopularVideos(page, size));
    }
    
    /**
     * Get videos uploaded by a specific user.
     * 
     * @param userId The ID of the user
     * @param page The page number (0-based)
     * @param size The page size
     * @return A page of video DTOs uploaded by the user
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<VideoDTO>> getUserVideos(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        return ResponseEntity.ok(catalogService.getUserVideos(userId, page, size));
    }
    
    /**
     * Get a video by ID.
     * 
     * @param videoId The ID of the video
     * @return The video DTO
     */
    @GetMapping("/{videoId}")
    public ResponseEntity<VideoDTO> getVideo(@PathVariable Long videoId) {
        return ResponseEntity.ok(catalogService.getVideo(videoId));
    }
    
    /**
     * Get a streaming manifest for a video.
     * 
     * @param videoId The ID of the video
     * @return The streaming manifest
     */
    @GetMapping("/{videoId}/manifest")
    public ResponseEntity<StreamingManifest> getManifest(@PathVariable Long videoId) {
        return ResponseEntity.ok(streamingService.generateManifest(videoId));
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
    @PostMapping
    public ResponseEntity<VideoDTO> createVideo(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam Integer durationSeconds,
            @RequestParam Long userId) {
        
        VideoDTO video = catalogService.createVideo(title, description, durationSeconds, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(video);
    }
    
    /**
     * Upload video content and start the transcoding process.
     * 
     * @param videoId The ID of the video
     * @param file The video file
     * @return A response indicating that the upload was accepted
     * @throws IOException if an I/O error occurs
     */
    @PostMapping("/{videoId}/upload")
    public ResponseEntity<String> uploadVideoContent(
            @PathVariable Long videoId,
            @RequestParam("file") MultipartFile file) throws IOException {
        
        log.info("Received video upload for video {}, size: {} bytes", videoId, file.getSize());
        
        // Start the upload and transcoding process asynchronously
        CompletableFuture<Void> future = catalogService.uploadVideoContent(videoId, file.getBytes());
        
        // Return immediately without waiting for the upload to complete
        return ResponseEntity.accepted().body("Video upload accepted for processing");
    }
    
    /**
     * Update a video's metadata.
     * 
     * @param videoId The ID of the video
     * @param title The new title (or null to keep the current value)
     * @param description The new description (or null to keep the current value)
     * @return The updated video DTO
     */
    @PutMapping("/{videoId}")
    public ResponseEntity<VideoDTO> updateVideo(
            @PathVariable Long videoId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description) {
        
        return ResponseEntity.ok(catalogService.updateVideo(videoId, title, description));
    }
    
    /**
     * Delete a video from the catalog.
     * 
     * @param videoId The ID of the video
     * @return A response with no content
     */
    @DeleteMapping("/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable Long videoId) {
        catalogService.deleteVideo(videoId);
        return ResponseEntity.noContent().build();
    }
}