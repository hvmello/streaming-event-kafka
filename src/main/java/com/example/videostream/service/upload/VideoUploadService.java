package com.example.videostream.service.upload;

import com.example.videostream.config.KafkaTopicConfig;
import com.example.videostream.config.StorageProperties;
import com.example.videostream.model.entity.OutboxEvent;
import com.example.videostream.model.entity.Video;
import com.example.videostream.model.event.VideoUploadedEvent;
import com.example.videostream.repository.OutboxEventRepository;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.ObjectStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Claim-check upload: the bytes go to the object store, the database row records the object key, and a small
 * JSON reference is queued for Kafka in the transactional outbox.
 *
 * <p>Order matters. The object is written first and without a database transaction. Then one short
 * transaction moves the video to {@code PROCESSING} and inserts the outbox row, so the state change and the
 * event commit together or not at all. {@code OutboxRelay} publishes the row afterwards, at-least-once. A crash
 * between the object write and the commit leaves an object with no row, which {@code OrphanObjectCleaner}
 * removes. This service never talks to Kafka: a broker outage delays the event but does not fail the upload.
 */
@Slf4j
@Service
public class VideoUploadService {

    private final VideoRepository videoRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectStore objectStore;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public VideoUploadService(VideoRepository videoRepository, OutboxEventRepository outboxRepository,
                              ObjectStore objectStore, StorageProperties storageProperties,
                              ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.videoRepository = videoRepository;
        this.outboxRepository = outboxRepository;
        this.objectStore = objectStore;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Stores the file and queues the event that announces it.
     *
     * @return the key of the raw object
     * @throws ResponseStatusException 400 for an empty file, 404 for an unknown video, 409 when the video is
     *                                 not in {@code UPLOADING}
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
            Integer updated = transactionTemplate.execute(status -> {
                int rows = videoRepository.markProcessing(videoId, key, size);
                if (rows == 1) {
                    VideoUploadedEvent event = new VideoUploadedEvent(videoId, contentId,
                            storageProperties.bucket(), key, size, contentType, Instant.now());
                    outboxRepository.save(new OutboxEvent(KafkaTopicConfig.VIDEO_UPLOADED, contentId,
                            toJson(event), Instant.now()));
                }
                return rows;
            });
            if (updated == null || updated == 0) {
                // Another request moved the video out of UPLOADING after the check above.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Video is not accepting uploads");
            }
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        return key;
    }

    private String toJson(VideoUploadedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the upload event", e);
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
