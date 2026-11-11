package com.example.videostream.service.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Minimal object store used by the upload flow. Production uses {@link S3ObjectStore}; unit tests use an
 * in-memory fake.
 */
public interface ObjectStore {

    /**
     * An object listed from the store.
     */
    record StoredObject(String key, Instant lastModified) {
    }

    /**
     * Streams {@code size} bytes from {@code content} to {@code key}, replacing any existing object.
     */
    void put(String key, InputStream content, long size, String contentType);

    /**
     * Downloads the object to {@code target}, which must not exist yet. The bytes go to disk, never into the heap.
     */
    void get(String key, Path target);

    /**
     * An open object: the caller owns the stream and must close it, also when the client goes away mid-read.
     */
    record StoredContent(InputStream stream, long contentLength, String contentType) {
    }

    /**
     * Opens the object for streaming, or returns empty when the key does not exist. The bytes are never held
     * in the heap by the store.
     */
    Optional<StoredContent> open(String key);

    /**
     * Deletes the object; deleting a missing key is not an error.
     */
    void delete(String key);

    boolean exists(String key);

    /**
     * Walks every object whose key starts with {@code prefix}, one page at a time, so the whole listing is
     * never held in memory.
     */
    void forEachPage(String prefix, Consumer<List<StoredObject>> pageConsumer);
}
