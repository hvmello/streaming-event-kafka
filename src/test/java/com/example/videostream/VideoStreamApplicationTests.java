package com.example.videostream;

import com.example.videostream.controller.StreamingController;
import com.example.videostream.controller.VideoController;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots the real application context against throwaway Postgres and Kafka containers,
 * through the same properties path as production.
 * Docker is required; the test must fail loudly without it, never be skipped.
 */
@SpringBootTest
@Testcontainers
class VideoStreamApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")).withDatabaseName("videostream");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextHasSingleApplicationAndCoreBeans() {
        assertThat(context.getBeansWithAnnotation(SpringBootApplication.class)).hasSize(1);
        assertThat(context.getBean(VideoStreamApplication.class)).isNotNull();
        assertThat(context.getBean(VideoController.class)).isNotNull();
        assertThat(context.getBean(StreamingController.class)).isNotNull();
        assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
    }

    @Test
    void jdbcSettingsComeFromConfiguration() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
    }

    @Test
    void topicsAreCreatedOnASingleBroker() throws Exception {
        List<String> names = List.of("video-uploads", "transcoded-videos", "video-metadata");
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).containsAll(names));
            Map<String, TopicDescription> topics =
                    admin.describeTopics(names).allTopicNames().get(10, TimeUnit.SECONDS);
            topics.values().forEach(t -> t.partitions()
                    .forEach(p -> assertThat(p.replicas()).hasSize(1)));
        }
    }
}
