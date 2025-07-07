package com.example.videostream.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.TopicBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for video streaming and transcoding pipeline.
 * 
 * This configuration sets up Kafka Streams for processing video segments
 * in a high-throughput, low-latency manner. It leverages Java 21's virtual
 * threads to handle multiple stream processing tasks concurrently.
 */
@Configuration
@EnableKafka
@EnableKafkaStreams
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${videostream.kafka.application-id:video-stream-app}")
    private String applicationId;

    /**
     * Configure Kafka Streams for video processing pipeline.
     * 
     * The configuration optimizes for low-latency video segment processing
     * by setting appropriate buffer sizes and processing guarantees.
     */
    @Bean
    public KafkaStreamsConfiguration kafkaStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArray().getClass().getName());
        
        // Configure for low-latency processing
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024); // 10MB buffer
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        
        // Use virtual threads for stream processing tasks
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 16);
        
        return new KafkaStreamsConfiguration(props);
    }

    /**
     * Create topic for raw video uploads.
     */
    @Bean
    public NewTopic videoUploadTopic() {
        return TopicBuilder.name("video-uploads")
                .partitions(10)
                .replicas(3)
                .build();
    }

    /**
     * Create topic for transcoded video segments.
     */
    @Bean
    public NewTopic transcodedVideoTopic() {
        return TopicBuilder.name("transcoded-videos")
                .partitions(20)
                .replicas(3)
                .build();
    }

    /**
     * Create topic for video metadata updates.
     */
    @Bean
    public NewTopic videoMetadataTopic() {
        return TopicBuilder.name("video-metadata")
                .partitions(5)
                .replicas(3)
                .build();
    }
}