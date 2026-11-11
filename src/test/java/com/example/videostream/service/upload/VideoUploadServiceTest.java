package com.example.videostream.service.upload;

import com.example.videostream.config.StorageProperties;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.InMemoryObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoUploadServiceTest {

    private static final long VIDEO_ID = 7L;
    private static final String CONTENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final byte[] BYTES = "not really a video".getBytes(StandardCharsets.UTF_8);

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private VideoUploadService service;
    private Video video;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties("http://localhost:8333", "us-east-1", "videos",
                "a", "s", Duration.ofHours(1), Duration.ofMinutes(10), true);
        service = new VideoUploadService(videoRepository, store, properties, kafkaTemplate, objectMapper,
                transactionManager);
        video = new Video();
        video.setId(VIDEO_ID);
        video.setContentId(CONTENT_ID);
        video.setStatus(Video.VideoStatus.UPLOADING);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "clip.mp4", "video/mp4", BYTES);
    }

    private void videoExists() {
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.of(video));
    }

    @Test
    void writesTheObjectBeforeTheRowUpdateAndPublishesAfterwards() {
        videoExists();
        List<String> order = new ArrayList<>();
        when(videoRepository.markProcessing(eq(VIDEO_ID), anyString(), anyLong())).thenAnswer(inv -> {
            order.add("row update; object present: " + store.exists(inv.getArgument(1)));
            return 1;
        });
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            order.add("publish");
            return CompletableFuture.completedFuture(null);
        });

        String key = service.upload(VIDEO_ID, file());

        assertThat(order).containsExactly("row update; object present: true", "publish");
        assertThat(store.bytes(key)).isEqualTo(BYTES);
    }

    @Test
    void keyHasTheRawContentIdAndUploadIdShape() {
        videoExists();
        when(videoRepository.markProcessing(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String first = service.upload(VIDEO_ID, file());

        assertThat(first).matches("raw/" + CONTENT_ID + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        verify(videoRepository).markProcessing(VIDEO_ID, first, BYTES.length);
    }

    @Test
    void eventIsASmallJsonReferenceWithoutPayload() throws Exception {
        videoExists();
        when(videoRepository.markProcessing(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String key = service.upload(VIDEO_ID, file());

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("video.uploaded"), eq(CONTENT_ID), value.capture());
        JsonNode event = objectMapper.readTree(value.getValue());
        assertThat(event.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("videoId", "contentId", "bucket", "key", "sizeBytes", "contentType",
                        "uploadedAt");
        assertThat(event.get("videoId").asLong()).isEqualTo(VIDEO_ID);
        assertThat(event.get("contentId").asText()).isEqualTo(CONTENT_ID);
        assertThat(event.get("bucket").asText()).isEqualTo("videos");
        assertThat(event.get("key").asText()).isEqualTo(key);
        assertThat(event.get("sizeBytes").asLong()).isEqualTo(BYTES.length);
        assertThat(event.get("contentType").asText()).isEqualTo("video/mp4");
        assertThat(value.getValue().getBytes(StandardCharsets.UTF_8).length).isLessThan(1024);
    }

    @Test
    void rowUpdateFailureDeletesTheObjectAndSendsNoEvent() {
        videoExists();
        when(videoRepository.markProcessing(anyLong(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(VIDEO_ID, file()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.size()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void lostRaceOnTheStatusDeletesTheObjectAndReturns409() {
        videoExists();
        when(videoRepository.markProcessing(anyLong(), anyString(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.upload(VIDEO_ID, file()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(store.size()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void publishFailureSetsStatusError() {
        videoExists();
        when(videoRepository.markProcessing(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> service.upload(VIDEO_ID, file()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(video.getStatus()).isEqualTo(Video.VideoStatus.ERROR);
        verify(videoRepository).save(video);
    }

    @Test
    void emptyFileIsRejectedWith400() {
        MockMultipartFile empty = new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[0]);

        assertThatThrownBy(() -> service.upload(VIDEO_ID, empty))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(store.size()).isZero();
    }

    @Test
    void videoNotInUploadingIsRejectedWith409() {
        video.setStatus(Video.VideoStatus.PROCESSING);
        videoExists();

        assertThatThrownBy(() -> service.upload(VIDEO_ID, file()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(store.size()).isZero();
        verify(videoRepository, never()).markProcessing(anyLong(), anyString(), anyLong());
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(String.class));
    }

    @Test
    void unknownVideoIsRejectedWith404() {
        when(videoRepository.findById(VIDEO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(VIDEO_ID, file()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
