package com.example.videostream.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the pipeline topics from {@link KafkaTopicProperties}.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaTopicConfig {

    /** Claim-check references to raw uploads; the value never carries video bytes. */
    public static final String VIDEO_UPLOADED = "video.uploaded";

    @Bean
    public NewTopic videoUploadedTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name(VIDEO_UPLOADED)
                .partitions(properties.videoUploadedPartitions())
                .replicas(properties.replicas())
                .build();
    }

    @Bean
    public NewTopic videoMetadataTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name("video-metadata")
                .partitions(properties.videoMetadataPartitions())
                .replicas(properties.replicas())
                .build();
    }
}
