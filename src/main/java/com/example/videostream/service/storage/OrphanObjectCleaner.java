package com.example.videostream.service.storage;

import com.example.videostream.config.StorageProperties;
import com.example.videostream.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Deletes raw objects that no video row references. An upload writes the object first and commits the row
 * afterwards, so a crash or a failed commit can leave an orphan; the grace period keeps the cleaner away
 * from uploads whose transaction has not committed yet.
 */
@Slf4j
@Component
public class OrphanObjectCleaner {

    static final String RAW_PREFIX = "raw/";

    private final ObjectStore objectStore;
    private final VideoRepository videoRepository;
    private final StorageProperties properties;

    public OrphanObjectCleaner(ObjectStore objectStore, VideoRepository videoRepository,
                               StorageProperties properties) {
        this.objectStore = objectStore;
        this.videoRepository = videoRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${videostream.storage.cleanup-interval}",
            initialDelayString = "${videostream.storage.cleanup-interval}")
    public void scheduledRun() {
        runOnce(Instant.now());
    }

    /**
     * One cleanup pass.
     *
     * @param now the reference time for the grace period
     * @return the number of objects deleted
     */
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(properties.orphanGrace());
        int[] counts = new int[2]; // scanned, deleted
        objectStore.forEachPage(RAW_PREFIX, page -> {
            counts[0] += page.size();
            List<String> candidates = page.stream()
                    .filter(o -> !o.lastModified().isAfter(cutoff))
                    .map(ObjectStore.StoredObject::key)
                    .toList();
            if (candidates.isEmpty()) {
                return;
            }
            Set<String> referenced = videoRepository.findReferencedKeys(candidates);
            for (String key : candidates) {
                if (referenced.contains(key)) {
                    continue;
                }
                try {
                    objectStore.delete(key);
                    counts[1]++;
                } catch (RuntimeException e) {
                    log.warn("Could not delete orphan object {}", key, e);
                }
            }
        });
        log.info("Orphan cleanup: scanned {} objects, deleted {}", counts[0], counts[1]);
        return counts[1];
    }
}
