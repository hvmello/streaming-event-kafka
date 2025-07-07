package com.example.videostream.controller;

import com.example.videostream.model.entity.ViewingSession;
import com.example.videostream.model.stream.VideoSegment;
import com.example.videostream.service.streaming.adaptive.AdaptiveStreamingService;
import com.example.videostream.service.streaming.session.SessionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Controller for video streaming operations.
 * 
 * This controller provides REST endpoints for streaming video content,
 * including starting and managing viewing sessions, requesting video
 * segments, and adapting quality based on network conditions.
 */
@RestController
@RequestMapping("/api/streaming")
@RequiredArgsConstructor
@Slf4j
public class StreamingController {

    private final AdaptiveStreamingService streamingService;
    private final SessionManagementService sessionService;
    
    /**
     * Start a new viewing session.
     * 
     * @param videoId The ID of the video
     * @param userId The ID of the user (optional)
     * @param clientIp The client IP address
     * @param userAgent The client user agent
     * @return The created viewing session
     */
    @PostMapping("/sessions")
    public ResponseEntity<ViewingSession> startSession(
            @RequestParam Long videoId,
            @RequestParam(required = false) Long userId,
            @RequestHeader("X-Forwarded-For") String clientIp,
            @RequestHeader("User-Agent") String userAgent) {
        
        ViewingSession session = sessionService.startSession(videoId, userId, clientIp, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }
    
    /**
     * Update a viewing session with the current playback position.
     * 
     * @param sessionId The session ID
     * @param positionMs The current playback position in milliseconds
     * @param qualityLevel The current quality level
     * @return The updated viewing session
     */
    @PutMapping("/sessions/{sessionId}")
    public ResponseEntity<ViewingSession> updateSession(
            @PathVariable String sessionId,
            @RequestParam int positionMs,
            @RequestParam String qualityLevel) {
        
        ViewingSession session = sessionService.updateSession(sessionId, positionMs, qualityLevel);
        return ResponseEntity.ok(session);
    }
    
    /**
     * End a viewing session.
     * 
     * @param sessionId The session ID
     * @return A response with no content
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        sessionService.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Get a video segment.
     * 
     * This endpoint demonstrates the use of virtual threads for handling
     * concurrent segment requests with minimal overhead.
     * 
     * @param videoId The ID of the video
     * @param segmentNumber The segment number
     * @param qualityLevel The quality level
     * @param sessionId The viewing session ID
     * @return The video segment data
     */
    @GetMapping("/videos/{videoId}/segments/{segmentNumber}/{qualityLevel}")
    public ResponseEntity<byte[]> getSegment(
            @PathVariable String videoId,
            @PathVariable int segmentNumber,
            @PathVariable String qualityLevel,
            @RequestParam String sessionId) {
        
        try {
            // Get the segment asynchronously
            CompletableFuture<VideoSegment> future = 
                    streamingService.getSegmentAsync(videoId, segmentNumber, qualityLevel, sessionId);
            
            // Wait for the segment to be ready
            VideoSegment segment = future.join();
            
            // Return the segment data
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(segment.getContentType()))
                    .contentLength(segment.getSizeInBytes())
                    .body(segment.getData());
            
        } catch (Exception e) {
            log.error("Error getting segment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Stream video segments.
     * 
     * This endpoint demonstrates the use of reactive streams for video delivery,
     * combined with virtual threads for efficient I/O handling.
     * 
     * @param videoId The ID of the video
     * @param startSegment The starting segment number
     * @param qualityLevel The quality level
     * @param sessionId The viewing session ID
     * @return A streaming response with the video segments
     */
    @GetMapping(value = "/videos/{videoId}/stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamVideo(
            @PathVariable String videoId,
            @RequestParam(defaultValue = "0") int startSegment,
            @RequestParam(defaultValue = "480p") String qualityLevel,
            @RequestParam String sessionId) {
        
        // Create a publisher for the video segments
        Flow.Publisher<VideoSegment> publisher = 
                streamingService.streamSegments(videoId, startSegment, qualityLevel, sessionId);
        
        // Create a streaming response body that subscribes to the publisher
        StreamingResponseBody responseBody = outputStream -> {
            // Create a subscriber that writes segments to the output stream
            Flow.Subscriber<VideoSegment> subscriber = new Flow.Subscriber<>() {
                private Flow.Subscription subscription;
                
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    subscription.request(1); // Request the first segment
                }
                
                @Override
                public void onNext(VideoSegment segment) {
                    try {
                        // Write the segment data to the output stream
                        outputStream.write(segment.getData());
                        outputStream.flush();
                        
                        // Request the next segment
                        subscription.request(1);
                    } catch (Exception e) {
                        subscription.cancel();
                        log.error("Error writing segment to output stream", e);
                    }
                }
                
                @Override
                public void onError(Throwable throwable) {
                    log.error("Error in segment stream", throwable);
                }
                
                @Override
                public void onComplete() {
                    log.debug("Segment stream completed");
                }
            };
            
            // Subscribe to the publisher
            publisher.subscribe(subscriber);
        };
        
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseBody);
    }
    
    /**
     * Recommend the optimal quality level based on network conditions.
     * 
     * @param sessionId The viewing session ID
     * @param bandwidth The current bandwidth in kbps
     * @param currentQuality The current quality level
     * @return The recommended quality level
     */
    @GetMapping("/quality-recommendation")
    public CompletableFuture<ResponseEntity<String>> recommendQualityLevel(
            @RequestParam String sessionId,
            @RequestParam int bandwidth,
            @RequestParam String currentQuality) {
        
        return streamingService.recommendQualityLevel(sessionId, bandwidth, currentQuality)
                .toFuture()
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error recommending quality level", ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }
}