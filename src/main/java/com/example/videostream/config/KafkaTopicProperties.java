package com.example.videostream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Topic sizing. {@code replicas} must not exceed the number of brokers in the cluster.
 */
@ConfigurationProperties("videostream.kafka.topics")
public record KafkaTopicProperties(
        @DefaultValue("1") short replicas,
        @DefaultValue("10") int videoUploadsPartitions,
        @DefaultValue("20") int transcodedVideosPartitions,
        @DefaultValue("5") int videoMetadataPartitions) {
}
