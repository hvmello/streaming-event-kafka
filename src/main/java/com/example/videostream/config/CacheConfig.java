package com.example.videostream.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cache configuration for video segments.
 * 
 * This configuration sets up caching for popular video segments to reduce
 * latency and database load. It leverages Java 21's virtual threads for
 * cache maintenance operations.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Configure cache manager for video segments.
     * 
     * Creates separate caches for:
     * - Popular video segments (high hit rate content)
     * - Video metadata (frequently accessed information)
     * - User session data (for quick authentication)
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            new ConcurrentMapCache("videoSegments"),
            new ConcurrentMapCache("videoMetadata"),
            new ConcurrentMapCache("userSessions")
        ));
        
        // Start cache maintenance using virtual threads
        startCacheMaintenance();
        
        return cacheManager;
    }
    
    /**
     * Creates a scheduled executor service using virtual threads for cache maintenance.
     * 
     * This demonstrates the use of virtual threads for background tasks that don't
     * require dedicated OS threads, improving resource utilization.
     */
    private void startCacheMaintenance() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(
            1, 
            Thread.ofVirtual().name("cache-maintenance-", 0).factory()
        );
        
        executor.scheduleAtFixedRate(() -> {
            // Simulate cache eviction logic for least recently used segments
            System.out.println("Running cache maintenance with virtual thread: " + 
                               Thread.currentThread().getName());
        }, 1, 5, TimeUnit.MINUTES);
    }
}