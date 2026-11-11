package com.example.videostream;

import com.example.videostream.controller.VideoController;
import com.example.videostream.model.dto.UploadAcceptedDTO;
import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.OrphanObjectCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots the real application context against throwaway Postgres, Kafka and object store containers,
 * through the same properties path as production, and exercises the claim-check upload over HTTP.
 * Docker is required; the test must fail loudly without it, never be skipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VideoStreamApplicationTests {

    private static final String BUCKET = "videostream";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")).withDatabaseName("videostream");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    // Same image tag as compose.yaml. SeaweedFS reads these credentials only because no s3.json is mounted.
    @Container
    static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data", "-volume.max=0", "-master.volumeSizeLimitMB=64")
            .withEnv("AWS_ACCESS_KEY_ID", "test-access-key")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test-secret-key")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void objectStoreProperties(DynamicPropertyRegistry registry) {
        registry.add("videostream.storage.endpoint",
                () -> "http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(8333));
        registry.add("videostream.storage.access-key", () -> "test-access-key");
        registry.add("videostream.storage.secret-key", () -> "test-secret-key");
        // Orphans are deleted regardless of age, so the cleanup test does not depend on the clock.
        registry.add("videostream.storage.orphan-grace", () -> "PT0S");
        // These tests inspect rows right after the upload; the transcode worker would move them. It is covered
        // by TranscodeEndToEndIT and LongJobNoRebalanceIT.
        registry.add("videostream.transcode.enabled", () -> "false");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private S3Client s3;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private OrphanObjectCleaner cleaner;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextHasSingleApplicationAndCoreBeans() {
        assertThat(context.getBeansWithAnnotation(SpringBootApplication.class)).hasSize(1);
        assertThat(context.getBean(VideoStreamApplication.class)).isNotNull();
        assertThat(context.getBean(VideoController.class)).isNotNull();
        assertThat(context.getBean(EntityManagerFactory.class)).isNotNull();
    }

    @Test
    void removedEndpointsAreNotMapped() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        List<String> patterns = mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .toList();
        assertThat(patterns).isNotEmpty();
        assertThat(patterns).noneMatch(p -> p.startsWith("/api/streaming") || p.contains("/user/") || p.contains("/manifest"));
    }

    @Test
    void jdbcSettingsComeFromConfiguration() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
    }

    @Test
    void kafkaStreamsUsesTheContainerBroker() {
        KafkaStreamsConfiguration streamsConfig = context.getBean(
                KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME, KafkaStreamsConfiguration.class);
        assertThat(streamsConfig.asProperties().getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo(KAFKA.getBootstrapServers());
    }

    @Test
    void kafkaStreamsDefaultsDoNotUseBytes() {
        KafkaStreamsConfiguration streamsConfig = context.getBean(
                KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME, KafkaStreamsConfiguration.class);
        assertThat(streamsConfig.asProperties().getProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG))
                .contains("StringSerde");
    }

    @Test
    void kafkaBootstrapServersAreDeclaredInConfiguration() {
        // The container connection details override this value at runtime; the property must exist so the
        // application does not rely on an implicit Boot default (override with KAFKA_BOOTSTRAP_SERVERS).
        assertThat(environment.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:9092");
    }

    @Test
    void topicsAreCreatedOnASingleBroker() throws Exception {
        List<String> names = List.of("video.uploaded", "video-metadata");
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

    @Test
    void uploadLimitIsConfiguredAboveBootDefault() {
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("512MB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("512MB");
    }

    @Test
    void uploadStoresTheObjectAndKafkaCarriesOnlyAReference() throws Exception {
        byte[] video = randomBytes(2 * 1024 * 1024, 42);
        VideoDTO created = createVideo();

        ResponseEntity<UploadAcceptedDTO> response = upload(created.getId(), video, UploadAcceptedDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String key = response.getBody().objectKey();
        assertThat(key).startsWith("raw/" + created.getContentId() + "/");

        // The object in the bucket has the same size and SHA-256 as the upload.
        byte[] stored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .asByteArray();
        assertThat(stored).hasSize(video.length);
        assertThat(sha256(stored)).isEqualTo(sha256(video));

        // The row has the key and PROCESSING.
        Video row = videoRepository.findById(created.getId()).orElseThrow();
        assertThat(row.getRawObjectKey()).isEqualTo(key);
        assertThat(row.getRawSizeBytes()).isEqualTo(video.length);
        assertThat(row.getStatus()).isEqualTo(Video.VideoStatus.PROCESSING);

        // The relay publishes the outbox row shortly after the commit; exactly one record names the key.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(readTopic("video.uploaded").stream()
                        .filter(r -> new String(r.value()).contains(key)).count()).isEqualTo(1));
        List<ConsumerRecord<byte[], byte[]>> uploaded = readTopic("video.uploaded").stream()
                .filter(r -> new String(r.value()).contains(key))
                .toList();
        assertThat(uploaded).hasSize(1);
        JsonNode event = objectMapper.readTree(uploaded.get(0).value());
        assertThat(event.get("key").asText()).isEqualTo(key);
        assertThat(event.get("bucket").asText()).isEqualTo(BUCKET);
        assertThat(event.get("videoId").asLong()).isEqualTo(created.getId());
        assertThat(event.get("sizeBytes").asLong()).isEqualTo(video.length);
        assertThat(new String(uploaded.get(0).key())).isEqualTo(created.getContentId());

        // Done criterion 1: no topic carries video bytes.
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            List<String> topics = new ArrayList<>(admin.listTopics().names().get(10, TimeUnit.SECONDS));
            assertThat(topics).contains("video.uploaded", "video-metadata");
            List<byte[]> windows = List.of(
                    Arrays.copyOfRange(video, 0, 64),
                    Arrays.copyOfRange(video, video.length / 2, video.length / 2 + 64),
                    Arrays.copyOfRange(video, video.length - 64, video.length));
            for (String topic : topics) {
                for (ConsumerRecord<byte[], byte[]> record : readTopic(topic)) {
                    byte[] value = record.value() == null ? new byte[0] : record.value();
                    assertThat(value.length).as("record size in " + topic).isLessThanOrEqualTo(4096);
                    for (byte[] window : windows) {
                        assertThat(indexOf(value, window)).as("video bytes in " + topic).isEqualTo(-1);
                    }
                }
            }
        }
    }

    @Test
    void secondUploadIsRejectedWith409AndLeavesNoExtraObject() {
        VideoDTO created = createVideo();
        String prefix = "raw/" + created.getContentId() + "/";
        ResponseEntity<UploadAcceptedDTO> first = upload(created.getId(), randomBytes(1024, 1), UploadAcceptedDTO.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> second = upload(created.getId(), randomBytes(1024, 2), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(s3.listObjectsV2(b -> b.bucket(BUCKET).prefix(prefix)).contents()).hasSize(1);
        assertThat(videoRepository.findById(created.getId()).orElseThrow().getRawObjectKey())
                .isEqualTo(first.getBody().objectKey());
    }

    @Test
    void emptyUploadIsRejectedWith400() {
        VideoDTO created = createVideo();

        ResponseEntity<String> response = upload(created.getId(), new byte[0], String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cleanerDeletesAnOrphanAndKeepsTheReferencedObject() {
        VideoDTO created = createVideo();
        String key = upload(created.getId(), randomBytes(4096, 3), UploadAcceptedDTO.class).getBody().objectKey();
        String orphan = "raw/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(orphan).build(),
                RequestBody.fromBytes(new byte[]{1, 2, 3}));

        // Done criterion 2. The grace period is PT0S; the reference time leaves room for clock skew
        // between the host and the container.
        int deleted = cleaner.runOnce(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(exists(orphan)).isFalse();
        assertThat(exists(key)).isTrue();
    }

    private boolean exists(String key) {
        return !s3.listObjectsV2(b -> b.bucket(BUCKET).prefix(key)).contents().isEmpty();
    }

    private VideoDTO createVideo() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("title", "clip");
        form.add("description", "test clip");
        form.add("durationSeconds", "60");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<VideoDTO> response =
                http.postForEntity("/api/videos", new HttpEntity<>(form, headers), VideoDTO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> ResponseEntity<T> upload(Long videoId, byte[] bytes, Class<T> type) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "clip.mp4";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/videos/" + videoId + "/upload", new HttpEntity<>(body, headers), type);
    }

    /** Reads every record of a topic from the earliest offset up to the end offsets at call time. */
    private List<ConsumerRecord<byte[], byte[]>> readTopic(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = new HashMap<>(consumer.endOffsets(partitions));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                assertThat(System.nanoTime()).as("timed out reading " + topic).isLessThan(deadline);
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
