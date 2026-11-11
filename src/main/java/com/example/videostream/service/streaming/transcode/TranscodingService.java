package com.example.videostream.service.streaming.transcode;

import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Service for video status and metadata updates driven by Kafka Streams.
 *
 * Raw uploads no longer pass through Kafka: the upload flow stores the object and publishes a reference to
 * {@code video.uploaded}. Only the metadata stream remains here, until it is replaced by the real pipeline.
 */
@Service
@EnableKafkaStreams
@RequiredArgsConstructor
@Slf4j
public class TranscodingService {

    private final VideoRepository videoRepository;

    // Standard quality levels for transcoding
    private static final Set<String> QUALITY_LEVELS = new HashSet<>(
            Arrays.asList("1080p", "720p", "480p", "360p", "240p"));

    /**
     * Configure the Kafka Streams topology. Kafka Streams needs at least one source topic to start, so the
     * metadata stream stays for now.
     *
     * @param streamsBuilder The Kafka Streams builder
     * @return The configured builder
     */
    @Bean
    public StreamsBuilder transcodingPipeline(StreamsBuilder streamsBuilder) {
        KStream<String, String> metadataUpdates = streamsBuilder
                .stream("video-metadata", Consumed.with(Serdes.String(), Serdes.String()));

        metadataUpdates
            .peek((videoId, metadata) -> log.info("Processing metadata update for video: {}", videoId))
            .foreach((videoId, metadata) -> updateVideoMetadata(videoId, metadata));

        return streamsBuilder;
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