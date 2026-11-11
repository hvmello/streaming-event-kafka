package com.example.videostream.service.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
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
