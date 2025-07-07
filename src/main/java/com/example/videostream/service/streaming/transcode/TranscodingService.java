package com.example.videostream.service.streaming.transcode;

import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for video transcoding using Kafka Streams.
 * 
 * This service demonstrates the use of Kafka Streams for building a
 * scalable video transcoding pipeline. It leverages Java 21's virtual
 * threads for efficient parallel processing of video segments.
 */
@Service
@EnableKafkaStreams
@RequiredArgsConstructor
@Slf4j
public class TranscodingService {

    private final VideoRepository videoRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    
    // Standard quality levels for transcoding
    private static final Set<String> QUALITY_LEVELS = new HashSet<>(
            Arrays.asList("1080p", "720p", "480p", "360p", "240p"));
    
    // Create a virtual thread executor for transcoding tasks
    private final ExecutorService transcodingExecutor = 
            Executors.newVirtualThreadPerTaskExecutor();
    
    /**
     * Configure the Kafka Streams topology for video transcoding.
     * 
     * This method sets up a streaming pipeline that:
     * 1. Consumes raw video uploads
     * 2. Splits them into segments
     * 3. Transcodes each segment into multiple quality levels
     * 4. Produces the transcoded segments to output topics
     * 
     * @param streamsBuilder The Kafka Streams builder
     * @return The configured builder
     */
    @Bean
    public StreamsBuilder transcodingPipeline(StreamsBuilder streamsBuilder) {
        // Define the input stream of raw video uploads
        KStream<String, byte[]> videoUploads = streamsBuilder
                .stream("video-uploads", Consumed.with(Serdes.String(), Serdes.ByteArray()));
        
        // Process the video uploads
        videoUploads
            // Log each upload
            .peek((videoId, data) -> log.info("Received video upload for processing: {}, size: {} bytes", 
                    videoId, data.length))
            
            // Split the video into segments and transcode each segment
            .flatMapValues(this::splitAndTranscodeVideo)
            
            // Send the transcoded segments to the output topic
            .to("transcoded-videos", Produced.with(Serdes.String(), Serdes.ByteArray()));
        
        // Define a stream for video metadata updates
        KStream<String, String> metadataUpdates = streamsBuilder
                .stream("video-metadata", Consumed.with(Serdes.String(), Serdes.String()));
        
        // Process metadata updates
        metadataUpdates
            .peek((videoId, metadata) -> log.info("Processing metadata update for video: {}", videoId))
            .foreach((videoId, metadata) -> updateVideoMetadata(videoId, metadata));
        
        return streamsBuilder;
    }
    
    /**
     * Submit a video for transcoding.
     * 
     * @param videoId The ID of the video to transcode
     * @param rawData The raw video data
     * @return A CompletableFuture that completes when transcoding starts
     */
    public CompletableFuture<Void> submitForTranscoding(String videoId, byte[] rawData) {
        return CompletableFuture.runAsync(() -> {
            log.info("Submitting video {} for transcoding, size: {} bytes", videoId, rawData.length);
            
            // Update video status to PROCESSING
            updateVideoStatus(videoId, Video.VideoStatus.PROCESSING);
            
            // Send the raw video data to the Kafka topic for processing
            kafkaTemplate.send("video-uploads", videoId, rawData)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to submit video for transcoding", ex);
                            updateVideoStatus(videoId, Video.VideoStatus.ERROR);
                        } else {
                            log.info("Video submitted for transcoding: {}", videoId);
                            updateVideoStatus(videoId, Video.VideoStatus.TRANSCODING);
                        }
                    });
        }, transcodingExecutor);
    }
    
    /**
     * Split a video into segments and transcode each segment.
     * 
     * In a real implementation, this would use a video processing library.
     * For this example, we'll simulate the process.
     * 
     * @param rawData The raw video data
     * @return A list of transcoded segments
     */
    private Iterable<byte[]> splitAndTranscodeVideo(byte[] rawData) {
        // In a real implementation, this would use a video processing library
        // to split the video into segments and transcode each segment
        
        // For this example, we'll just create some dummy segments
        return Arrays.asList(
                simulateTranscodedSegment(rawData, 0),
                simulateTranscodedSegment(rawData, 1),
                simulateTranscodedSegment(rawData, 2)
        );
    }
    
    /**
     * Simulate transcoding a segment of video.
     * 
     * @param rawData The raw video data
     * @param segmentIndex The segment index
     * @return The transcoded segment data
     */
    private byte[] simulateTranscodedSegment(byte[] rawData, int segmentIndex) {
        // In a real implementation, this would use a video processing library
        // For this example, we'll just create a dummy segment
        
        try {
            // Simulate processing time
            Thread.sleep(100);
            
            // Create a dummy segment (in reality, this would be the transcoded data)
            byte[] segment = new byte[1024 * 1024]; // 1MB
            
            log.debug("Transcoded segment {} of video", segmentIndex);
            
            return segment;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Transcoding interrupted", e);
        }
    }
    
    /**
     * Update the status of a video in the database.
     * 
     * @param videoId The content ID of the video
     * @param status The new status
     */
    @Transactional
    public void updateVideoStatus(String videoId, Video.VideoStatus status) {
        videoRepository.findByContentId(videoId).ifPresent(video -> {
            video.setStatus(status);
            
            // If the video is ready, set the available quality levels
            if (status == Video.VideoStatus.READY) {
                video.setAvailableQualityLevels(QUALITY_LEVELS);
            }
            
            videoRepository.save(video);
            log.info("Updated video status: {} -> {}", videoId, status);
        });
    }
    
    /**
     * Update video metadata from a Kafka message.
     * 
     * @param videoId The content ID of the video
     * @param metadata The metadata JSON
     */
    @Transactional
    public void updateVideoMetadata(String videoId, String metadata) {
        // In a real implementation, this would parse the metadata JSON
        // and update the video entity accordingly
        
        log.info("Updating metadata for video: {}", videoId);
        
        // For this example, we'll just mark the video as READY
        updateVideoStatus(videoId, Video.VideoStatus.READY);
    }
}