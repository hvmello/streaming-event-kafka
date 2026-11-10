package com.example.videostream.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTopicConfig.class);

    @Test
    void defaultsToOneReplicaAndKeepsPartitionCounts() {
        runner.run(ctx -> {
            assertThat(ctx.getBeansOfType(NewTopic.class)).hasSize(3);
            assertThat(ctx.getBean("videoUploadTopic", NewTopic.class).numPartitions()).isEqualTo(10);
            assertThat(ctx.getBean("transcodedVideoTopic", NewTopic.class).numPartitions()).isEqualTo(20);
            assertThat(ctx.getBean("videoMetadataTopic", NewTopic.class).numPartitions()).isEqualTo(5);
            ctx.getBeansOfType(NewTopic.class).values()
                    .forEach(t -> assertThat(t.replicationFactor()).isEqualTo((short) 1));
        });
    }

    @Test
    void replicasAreConfigurable() {
        runner.withPropertyValues("videostream.kafka.topics.replicas=3").run(ctx ->
                ctx.getBeansOfType(NewTopic.class).values()
                        .forEach(t -> assertThat(t.replicationFactor()).isEqualTo((short) 3)));
    }
}
