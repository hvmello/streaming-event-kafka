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

    @Bean
    public NewTopic videoUploadTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name("video-uploads")
                .partitions(properties.videoUploadsPartitions())
                .replicas(properties.replicas())
                .build();
    }

    @Bean
    public NewTopic transcodedVideoTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name("transcoded-videos")
                .partitions(properties.transcodedVideosPartitions())
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
