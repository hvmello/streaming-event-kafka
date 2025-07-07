package com.example.videostream.service.streaming.adaptive;

import com.example.videostream.model.stream.VideoSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publisher for streaming video segments using Java's Flow API.
 * 
 * This class demonstrates the use of reactive streams for video delivery,
 * combined with virtual threads for efficient I/O handling.
 */
@Slf4j
@RequiredArgsConstructor
public class SegmentPublisher implements Flow.Publisher<VideoSegment> {

    private final String videoId;
    private final int startSegment;
    private final String initialQuality;
    private final String sessionId;
    private final AdaptiveStreamingService streamingService;
    
    @Override
    public void subscribe(Flow.Subscriber<? super VideoSegment> subscriber) {
        // Create a new SegmentSubscription for this subscriber
        SegmentSubscription subscription = new SegmentSubscription(
                subscriber, videoId, startSegment, initialQuality, sessionId, streamingService);
        
        // Signal the subscription to the subscriber
        subscriber.onSubscribe(subscription);
    }
    
    /**
     * Subscription that manages the delivery of video segments to a subscriber.
     */
    private static class SegmentSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super VideoSegment> subscriber;
        private final String videoId;
        private final String sessionId;
        private final AdaptiveStreamingService streamingService;
        
        private final AtomicInteger nextSegment = new AtomicInteger();
        private final AtomicInteger demand = new AtomicInteger(0);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private String currentQuality;
        
        /**
         * Create a new segment subscription.
         * 
         * @param subscriber The subscriber to deliver segments to
         * @param videoId The ID of the video
         * @param startSegment The starting segment number
         * @param initialQuality The initial quality level
         * @param sessionId The viewing session ID
         * @param streamingService The streaming service to get segments from
         */
        public SegmentSubscription(
                Flow.Subscriber<? super VideoSegment> subscriber,
                String videoId,
                int startSegment,
                String initialQuality,
                String sessionId,
                AdaptiveStreamingService streamingService) {
            
            this.subscriber = subscriber;
            this.videoId = videoId;
            this.sessionId = sessionId;
            this.streamingService = streamingService;
            this.nextSegment.set(startSegment);
            this.currentQuality = initialQuality;
            
            log.info("Created new segment subscription for video {} starting at segment {} with quality {}",
                    videoId, startSegment, initialQuality);
        }
        
        @Override
        public void request(long n) {
            if (cancelled.get()) {
                return;
            }
            
            // Add the requested number of items to the demand
            int updatedDemand = demand.addAndGet((int) n);
            log.debug("Subscriber requested {} more segments, total demand: {}", n, updatedDemand);
            
            // Start delivering segments if we weren't already
            if (updatedDemand > 0) {
                deliverSegments();
            }
        }
        
        @Override
        public void cancel() {
            log.info("Subscription cancelled for video {} session {}", videoId, sessionId);
            cancelled.set(true);
        }
        
        /**
         * Deliver segments to the subscriber based on demand.
         */
        private void deliverSegments() {
            // Use a virtual thread to handle segment delivery
            Thread.startVirtualThread(() -> {
                while (demand.get() > 0 && !cancelled.get()) {
                    int segmentNumber = nextSegment.getAndIncrement();
                    
                    try {
                        // Get the segment asynchronously
                        CompletableFuture<VideoSegment> segmentFuture = 
                                streamingService.getSegmentAsync(videoId, segmentNumber, currentQuality, sessionId);
                        
                        // When the segment is ready, deliver it to the subscriber
                        segmentFuture.thenAccept(segment -> {
                            if (!cancelled.get()) {
                                try {
                                    subscriber.onNext(segment);
                                    demand.decrementAndGet();
                                    
                                    // Periodically check if we should adapt the quality
                                    if (segmentNumber % 5 == 0) {
                                        adaptQuality();
                                    }
                                } catch (Exception e) {
                                    log.error("Error delivering segment to subscriber", e);
                                    subscriber.onError(e);
                                    cancelled.set(true);
                                }
                            }
                        }).exceptionally(ex -> {
                            if (!cancelled.get()) {
                                log.error("Error getting segment", ex);
                                subscriber.onError(ex);
                                cancelled.set(true);
                            }
                            return null;
                        });
                        
                        // Simulate some delay between segment requests
                        Thread.sleep(100);
                        
                    } catch (Exception e) {
                        if (!cancelled.get()) {
                            log.error("Error in segment delivery", e);
                            subscriber.onError(e);
                            cancelled.set(true);
                        }
                        break;
                    }
                }
                
                // If we've been cancelled, we're done
                if (cancelled.get()) {
                    log.debug("Segment delivery cancelled");
                }
            });
        }
        
        /**
         * Adapt the quality level based on network conditions.
         */
        private void adaptQuality() {
            // In a real implementation, this would use bandwidth measurements
            // For this example, we'll just simulate some quality changes
            streamingService.recommendQualityLevel(sessionId, 4000, currentQuality)
                    .subscribe(newQuality -> {
                        if (!newQuality.equals(currentQuality)) {
                            log.info("Adapting quality from {} to {} for session {}",
                                    currentQuality, newQuality, sessionId);
                            currentQuality = newQuality;
                        }
                    });
        }
    }
}