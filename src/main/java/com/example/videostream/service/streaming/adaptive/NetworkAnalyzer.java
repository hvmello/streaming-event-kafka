package com.example.videostream.service.streaming.adaptive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component for analyzing network conditions and determining optimal streaming quality.
 * 
 * This component uses bandwidth measurements and other metrics to determine
 * the optimal quality level for adaptive streaming, ensuring smooth playback
 * while maximizing video quality.
 */
@Component
@Slf4j
public class NetworkAnalyzer {

    // Quality levels in descending order of quality
    private static final List<String> QUALITY_LEVELS = Arrays.asList(
            "1080p", "720p", "480p", "360p", "240p"
    );
    
    // Minimum bandwidth required for each quality level (in kbps)
    private static final Map<String, Integer> QUALITY_BANDWIDTH_REQUIREMENTS = Map.of(
            "1080p", 6000,
            "720p", 3500,
            "480p", 2000,
            "360p", 1000,
            "240p", 500
    );
    
    // Store recent bandwidth measurements for each session
    private final Map<String, BandwidthHistory> sessionBandwidthHistory = new ConcurrentHashMap<>();
    
    /**
     * Determine the optimal quality level based on network conditions.
     * 
     * This method implements an adaptive bitrate algorithm that considers:
     * 1. Current bandwidth
     * 2. Bandwidth stability (variance)
     * 3. Current quality level (to avoid frequent switching)
     * 
     * @param sessionId The viewing session ID
     * @param currentBandwidth The current bandwidth in kbps
     * @param currentQuality The current quality level
     * @return The recommended quality level
     */
    public String determineOptimalQuality(String sessionId, int currentBandwidth, String currentQuality) {
        // Update bandwidth history for this session
        BandwidthHistory history = sessionBandwidthHistory.computeIfAbsent(
                sessionId, k -> new BandwidthHistory());
        history.addMeasurement(currentBandwidth);
        
        // Get the average and variance of recent bandwidth measurements
        double avgBandwidth = history.getAverageBandwidth();
        double variance = history.getBandwidthVariance();
        
        // Apply a safety margin based on bandwidth stability
        // Higher variance = larger safety margin
        double stabilityFactor = Math.min(1.0, Math.max(0.5, 1.0 - (variance / avgBandwidth / 10)));
        double effectiveBandwidth = avgBandwidth * stabilityFactor;
        
        log.debug("Session {}: current={}, avg={}, effective={}, stability={}",
                sessionId, currentBandwidth, avgBandwidth, effectiveBandwidth, stabilityFactor);
        
        // Find the highest quality level that can be supported
        String recommendedQuality = "240p"; // Default to lowest quality
        for (String quality : QUALITY_LEVELS) {
            int requiredBandwidth = QUALITY_BANDWIDTH_REQUIREMENTS.get(quality);
            if (effectiveBandwidth >= requiredBandwidth * 1.2) { // 20% headroom
                recommendedQuality = quality;
                break;
            }
        }
        
        // Apply hysteresis to avoid frequent quality switches
        // Only switch if the recommended quality is significantly different
        if (currentQuality != null && !currentQuality.isEmpty()) {
            int currentIndex = QUALITY_LEVELS.indexOf(currentQuality);
            int recommendedIndex = QUALITY_LEVELS.indexOf(recommendedQuality);
            
            // If the difference is just one level, stick with the current quality
            // unless we've been at this bandwidth for a while
            if (Math.abs(currentIndex - recommendedIndex) == 1 && history.getMeasurementCount() < 5) {
                recommendedQuality = currentQuality;
            }
        }
        
        log.debug("Session {}: Recommending quality {} (current: {})",
                sessionId, recommendedQuality, currentQuality);
        
        return recommendedQuality;
    }
    
    /**
     * Class to track bandwidth history for a session.
     */
    private static class BandwidthHistory {
        private static final int MAX_HISTORY = 10;
        private final int[] measurements = new int[MAX_HISTORY];
        private int index = 0;
        private int count = 0;
        
        /**
         * Add a bandwidth measurement.
         * 
         * @param bandwidth The bandwidth in kbps
         */
        public void addMeasurement(int bandwidth) {
            measurements[index] = bandwidth;
            index = (index + 1) % MAX_HISTORY;
            if (count < MAX_HISTORY) {
                count++;
            }
        }
        
        /**
         * Get the average bandwidth from recent measurements.
         * 
         * @return The average bandwidth in kbps
         */
        public double getAverageBandwidth() {
            if (count == 0) {
                return 0;
            }
            
            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += measurements[i];
            }
            return (double) sum / count;
        }
        
        /**
         * Get the variance of bandwidth measurements.
         * 
         * @return The bandwidth variance
         */
        public double getBandwidthVariance() {
            if (count < 2) {
                return 0;
            }
            
            double avg = getAverageBandwidth();
            double sumSquaredDiff = 0;
            
            for (int i = 0; i < count; i++) {
                double diff = measurements[i] - avg;
                sumSquaredDiff += diff * diff;
            }
            
            return sumSquaredDiff / count;
        }
        
        /**
         * Get the number of measurements recorded.
         * 
         * @return The measurement count
         */
        public int getMeasurementCount() {
            return count;
        }
    }
}