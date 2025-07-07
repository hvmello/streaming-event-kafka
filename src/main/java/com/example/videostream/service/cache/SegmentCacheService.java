package com.example.videostream.service.cache;

import com.example.videostream.model.stream.VideoSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for caching video segments to improve streaming performance.
 * 
 * This service implements a cache for video segments to reduce latency and
 * backend load. It uses a combination of in-memory caching and distributed
 * caching (simulated) to provide fast access to popular segments.
 */
@Service
@Slf4j
public class SegmentCacheService {

    // In-memory cache for fastest access to hot segments
    private final Map<String, VideoSegment> localCache = new ConcurrentHashMap<>();
    
    // Track popularity of segments for cache eviction decisions
    private final Map<String, AtomicInteger> accessCounts = new ConcurrentHashMap<>();
    
    /**
     * Get a segment from the cache.
     * 
     * @param videoId The ID of the video
     * @param segmentNumber The segment number
     * @param qualityLevel The quality level
     * @return The cached segment, or null if not found
     */
    @Cacheable(value = "videoSegments", key = "#videoId + '-' + #segmentNumber + '-' + #qualityLevel", unless = "#result == null")
    public VideoSegment getSegment(String videoId, int segmentNumber, String qualityLevel) {
        String cacheKey = generateCacheKey(videoId, segmentNumber, qualityLevel);
        
        // Update access count for this segment
        accessCounts.computeIfAbsent(cacheKey, k -> new AtomicInteger(0)).incrementAndGet();
        
        VideoSegment segment = localCache.get(cacheKey);
        if (segment != null) {
            log.debug("Local cache hit for segment: {}", cacheKey);
            segment.setLastAccessed(System.currentTimeMillis());
            segment.setAccessCount(segment.getAccessCount() + 1);
            return segment;
        }
        
        log.debug("Cache miss for segment: {}", cacheKey);
        return null;
    }
    
    /**
     * Cache a segment for future requests.
     * 
     * @param segment The segment to cache
     */
    public void cacheSegment(VideoSegment segment) {
        String cacheKey = generateCacheKey(segment.getVideoId(), segment.getSegmentNumber(), segment.getQualityLevel());
        
        // Update segment metadata for cache management
        segment.setLastAccessed(System.currentTimeMillis());
        segment.setAccessCount(accessCounts.getOrDefault(cacheKey, new AtomicInteger(0)).get());
        
        // Calculate popularity score based on access count and recency
        int popularityScore = calculatePopularityScore(segment);
        segment.setPopularityScore(popularityScore);
        
        // Only cache if it's popular enough or a keyframe (important for seeking)
        if (popularityScore > 10 || segment.isKeyframe()) {
            log.debug("Caching segment: {}", cacheKey);
            localCache.put(cacheKey, segment);
        }
    }
    
    /**
     * Evict a segment from the cache.
     * 
     * @param videoId The ID of the video
     * @param segmentNumber The segment number
     * @param qualityLevel The quality level
     */
    @CacheEvict(value = "videoSegments", key = "#videoId + '-' + #segmentNumber + '-' + #qualityLevel")
    public void evictSegment(String videoId, int segmentNumber, String qualityLevel) {
        String cacheKey = generateCacheKey(videoId, segmentNumber, qualityLevel);
        localCache.remove(cacheKey);
        log.debug("Evicted segment from cache: {}", cacheKey);
    }
    
    /**
     * Scheduled task to clean up the cache.
     * 
     * This method runs periodically to evict unpopular segments from the cache.
     * It demonstrates the use of virtual threads for background maintenance tasks.
     */
    @Scheduled(fixedRate = 300000) // Run every 5 minutes
    public void cleanupCache() {
        Thread.startVirtualThread(() -> {
            log.info("Running cache cleanup on virtual thread: {}", Thread.currentThread().getName());
            
            long now = System.currentTimeMillis();
            long oldestAllowed = now - (30 * 60 * 1000); // 30 minutes
            
            localCache.entrySet().removeIf(entry -> {
                VideoSegment segment = entry.getValue();
                
                // Keep segments that are:
                // 1. Recently accessed, or
                // 2. Very popular, or
                // 3. Keyframes (important for seeking)
                boolean shouldKeep = segment.getLastAccessed() > oldestAllowed ||
                                    segment.getPopularityScore() > 50 ||
                                    segment.isKeyframe();
                
                return !shouldKeep;
            });
            
            log.info("Cache cleanup complete. Current cache size: {}", localCache.size());
        });
    }
    
    /**
     * Generate a cache key for a segment.
     * 
     * @param videoId The ID of the video
     * @param segmentNumber The segment number
     * @param qualityLevel The quality level
     * @return The cache key
     */
    private String generateCacheKey(String videoId, int segmentNumber, String qualityLevel) {
        return videoId + "-" + segmentNumber + "-" + qualityLevel;
    }
    
    /**
     * Calculate a popularity score for a segment based on access patterns.
     * 
     * @param segment The segment to score
     * @return The popularity score
     */
    private int calculatePopularityScore(VideoSegment segment) {
        // Base score is the access count
        int score = segment.getAccessCount();
        
        // Bonus for recent access
        long now = System.currentTimeMillis();
        long ageMs = now - segment.getLastAccessed();
        if (ageMs < 60000) { // Less than 1 minute old
            score += 30;
        } else if (ageMs < 300000) { // Less than 5 minutes old
            score += 20;
        } else if (ageMs < 900000) { // Less than 15 minutes old
            score += 10;
        }
        
        // Bonus for keyframes (important for seeking)
        if (segment.isKeyframe()) {
            score += 15;
        }
        
        return score;
    }
}