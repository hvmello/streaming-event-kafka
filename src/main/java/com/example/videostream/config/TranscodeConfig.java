package com.example.videostream.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer settings of the transcode worker: one record per poll, manual acknowledgement, and a group of its
 * own so it does not share offsets with anything else on {@code video.uploaded}.
 */
@Configuration
@EnableConfigurationProperties(TranscodeProperties.class)
public class TranscodeConfig {

    public static final String LISTENER_ID = "videostream-transcoder";
    public static final String GROUP_ID = "videostream-transcoder";
    public static final String CONTAINER_FACTORY = "transcodeListenerContainerFactory";

    /**
     * Starts from the auto-configured consumer settings, so the broker address (including a test container's)
     * is the one the rest of the application uses, and sets everything that matters for correctness
     * explicitly: the Spring Cloud Stream binder on the classpath otherwise changes the default
     * (de)serializers to byte arrays.
     */
    @Bean(CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> transcodeListenerContainerFactory(
            ConsumerFactory<?, ?> autoConfigured, TranscodeProperties properties,
            ObjectProvider<ConsumerRebalanceListener> rebalanceListener) {
        Map<String, Object> config = new HashMap<>(autoConfigured.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) properties.maxPollInterval().toMillis());

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<String, String>(config));
        factory.setConcurrency(properties.concurrency());
        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL);
        container.setAsyncAcks(true);
        ConsumerRebalanceListener listener = rebalanceListener.getIfUnique();
        if (listener != null) {
            container.setConsumerRebalanceListener(listener);
        }
        return factory;
    }
}
