package com.example.videostream.service.storage;

import com.example.videostream.config.StorageProperties;
import com.example.videostream.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OrphanObjectCleanerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Mock
    private VideoRepository videoRepository;

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private OrphanObjectCleaner cleaner;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties("http://localhost:8333", "us-east-1", "bucket",
                "a", "s", Duration.ofHours(1), Duration.ofMinutes(10), true);
        cleaner = new OrphanObjectCleaner(store, videoRepository, properties);
        lenient().when(videoRepository.existsByRawObjectKey("raw/c1/referenced")).thenReturn(true);
    }

    private void put(String key, Instant lastModified) {
        store.put(key, new ByteArrayInputStream(new byte[]{1}), 1, "video/mp4", lastModified);
    }

    @Test
    void deletesAnUnreferencedObjectOlderThanTheGracePeriod() {
        put("raw/c1/orphan", NOW.minus(Duration.ofHours(2)));

        assertThat(cleaner.runOnce(NOW)).isEqualTo(1);

        assertThat(store.exists("raw/c1/orphan")).isFalse();
    }

    @Test
    void keepsAReferencedObjectEvenWhenOld() {
        put("raw/c1/referenced", NOW.minus(Duration.ofDays(3)));

        assertThat(cleaner.runOnce(NOW)).isZero();

        assertThat(store.exists("raw/c1/referenced")).isTrue();
    }

    @Test
    void keepsAnUnreferencedObjectYoungerThanTheGracePeriod() {
        put("raw/c1/in-flight", NOW.minus(Duration.ofMinutes(5)));

        assertThat(cleaner.runOnce(NOW)).isZero();

        assertThat(store.exists("raw/c1/in-flight")).isTrue();
    }

    @Test
    void ignoresObjectsOutsideTheRawPrefix() {
        put("other/c1/file", NOW.minus(Duration.ofDays(3)));

        assertThat(cleaner.runOnce(NOW)).isZero();

        assertThat(store.exists("other/c1/file")).isTrue();
    }

    @Test
    void aFailedDeleteDoesNotStopTheRun() {
        put("raw/c1/a", NOW.minus(Duration.ofHours(2)));
        put("raw/c1/b", NOW.minus(Duration.ofHours(2)));
        store.failOnDelete = true;

        assertThat(cleaner.runOnce(NOW)).isZero();

        assertThat(store.size()).isEqualTo(2);
    }
}
