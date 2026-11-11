package com.example.videostream.service.upload;

import com.example.videostream.config.KafkaTopicConfig;
import com.example.videostream.config.StorageProperties;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.ObjectStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Claim-check upload: the bytes go to the object store, the database row records the object key, and Kafka
 * gets a small JSON reference.
 *
 * <p>Order matters. The object is written first and without a database transaction; the row is updated in
 * one short transaction afterwards; the event is published after that commit. A crash between the commit and
 * the publish leaves a {@code PROCESSING} row with no event (a dual write, fixed by the outbox in #10). A
 * crash between the object write and the commit leaves an object with no row, which
 * {@code OrphanObjectCleaner} removes.
 */
@Slf4j
@Service
public class VideoUploadService {

    private static final long PUBLISH_TIMEOUT_SECONDS = 30;

    private final VideoRepository videoRepository;
    private final ObjectStore objectStore;
    private final StorageProperties storageProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public VideoUploadService(VideoRepository videoRepository, ObjectStore objectStore,
                              StorageProperties storageProperties, KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.videoRepository = videoRepository;
        this.objectStore = objectStore;
        this.storageProperties = storageProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Stores the file and announces it.
     *
     * @return the key of the raw object
     * @throws ResponseStatusException 400 for an empty file, 404 for an unknown video, 409 when the video is
     *                                 not in {@code UPLOADING}, 502 when the event could not be published
     */
    public String upload(Long videoId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (video.getStatus() != Video.VideoStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Video is not accepting uploads");
        }

        String contentId = video.getContentId();
        String key = "raw/" + contentId + "/" + UUID.randomUUID();
        long size = file.getSize();
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        try (InputStream content = file.getInputStream()) {
            objectStore.put(key, content, size, contentType);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the upload", e);
        }

        try {
            Integer updated = transactionTemplate.execute(status ->
                    videoRepository.markProcessing(videoId, key, size));
            if (updated == null || updated == 0) {
                // Another request moved the video out of UPLOADING after the check above.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Video is not accepting uploads");
            }
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }

        publish(new VideoUploadedEvent(videoId, contentId, storageProperties.bucket(), key, size, contentType,
                Instant.now()));
        return key;
    }

    private void publish(VideoUploadedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopicConfig.VIDEO_UPLOADED, event.contentId(), json)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markError(event.videoId());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Publish interrupted", e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException | RuntimeException e) {
            log.error("Could not publish upload event for video {}", event.videoId(), e);
            markError(event.videoId());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not publish the upload event", e);
        }
    }

    private void markError(Long videoId) {
        try {
            transactionTemplate.executeWithoutResult(status -> videoRepository.findById(videoId).ifPresent(v -> {
                v.setStatus(Video.VideoStatus.ERROR);
                videoRepository.save(v);
            }));
        } catch (RuntimeException e) {
            log.error("Could not mark video {} as ERROR", videoId, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            objectStore.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete object {} after a failed upload; the orphan cleaner will remove it", key, e);
        }
    }
}
