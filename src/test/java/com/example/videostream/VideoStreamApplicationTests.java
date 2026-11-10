package com.example.videostream;

import com.example.videostream.controller.StreamingController;
import com.example.videostream.controller.VideoController;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application context against throwaway Postgres and Kafka containers.
 * Docker is required; the test must fail loudly without it, never be skipped.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Testcontainers
class VideoStreamApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")).withDatabaseName("videostream");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /**
     * DatabaseConfig hardcodes the JDBC URL, so the test overrides the dataSource bean to
     * avoid ever touching localhost:5432. Issue #4 replaces this with @ServiceConnection.
     */
    @TestConfiguration
    static class ContainerDataSourceConfig {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUsername(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            return ds;
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextHasSingleApplicationAndCoreBeans() {
        assertThat(context.getBeansWithAnnotation(SpringBootApplication.class)).hasSize(1);
        assertThat(context.getBean(VideoStreamApplication.class)).isNotNull();
        assertThat(context.getBean(VideoController.class)).isNotNull();
        assertThat(context.getBean(StreamingController.class)).isNotNull();
        assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
    }
}
