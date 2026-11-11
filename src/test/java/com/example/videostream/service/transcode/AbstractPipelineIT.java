package com.example.videostream.service.transcode;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Postgres, Kafka and object store for the transcode integration tests. The containers are started once
 * per JVM (Testcontainers' reaper removes them afterwards), so every subclass can have its own Spring context
 * without the contexts pointing at stopped containers. Docker is required; the tests fail loudly without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPipelineIT {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")).withDatabaseName("videostream");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    // Same image tag as compose.yaml. SeaweedFS reads these credentials only because no s3.json is mounted.
    protected static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data", "-volume.max=0", "-master.volumeSizeLimitMB=64")
            .withEnv("AWS_ACCESS_KEY_ID", "test-access-key")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test-secret-key")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        POSTGRES.start();
        KAFKA.start();
        OBJECT_STORE.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("videostream.storage.endpoint",
                () -> "http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(8333));
        registry.add("videostream.storage.access-key", () -> "test-access-key");
        registry.add("videostream.storage.secret-key", () -> "test-secret-key");
    }

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected VideoRepository videoRepository;

    @Autowired
    protected TransactionOperations transactions;

    /** The collection is lazy, so it is read inside a transaction. */
    protected Set<String> qualityLevels(Long videoId) {
        return transactions.execute(status ->
                new HashSet<>(videoRepository.findById(videoId).orElseThrow().getAvailableQualityLevels()));
    }

    protected VideoDTO createVideo() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("title", "clip");
        form.add("description", "test clip");
        form.add("durationSeconds", "12");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<VideoDTO> response =
                http.postForEntity("/api/videos", new HttpEntity<>(form, headers), VideoDTO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected void upload(Long videoId, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "clip.mp4";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = http.postForEntity("/api/videos/" + videoId + "/upload",
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    protected Video awaitStatus(Long videoId, Video.VideoStatus status, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(videoRepository.findById(videoId).orElseThrow().getStatus()).isEqualTo(status));
        return videoRepository.findById(videoId).orElseThrow();
    }
}
