package com.example.videostream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main application class for the VideoStream service.
 * 
 * This application implements a low-latency video streaming system using Java 21's
 * advanced multithreading capabilities including Virtual Threads (Project Loom)
 * and Structured Concurrency.
 * 
 * Key features:
 * - Adaptive video streaming with Virtual Threads
 * - Session management with Structured Concurrency
 * - Video transcoding pipeline with Kafka Streams
 * - Caching of popular video segments
 * - PostgreSQL for metadata storage
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class VideoStreamApplication {

    public static void main(String[] args) {
        // Configure the application to use virtual threads for servlet requests
        System.setProperty("spring.threads.virtual.enabled", "true");
        
        SpringApplication.run(VideoStreamApplication.class, args);
    }
}