package com.example.videostream.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory {@link ObjectStore} for unit tests.
 */
public class InMemoryObjectStore implements ObjectStore {

    private record Entry(byte[] bytes, String contentType, Instant lastModified) {
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    /** Set to make {@link #delete} fail, to test best-effort cleanup. */
    public boolean failOnDelete;

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        put(key, content, size, contentType, Instant.now());
    }

    public void put(String key, InputStream content, long size, String contentType, Instant lastModified) {
        try {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != size) {
                throw new IllegalArgumentException("declared " + size + " bytes but got " + bytes.length);
            }
            entries.put(key, new Entry(bytes, contentType, lastModified));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        if (failOnDelete) {
            throw new IllegalStateException("delete failed");
        }
        entries.remove(key);
    }

    @Override
    public boolean exists(String key) {
        return entries.containsKey(key);
    }

    @Override
    public List<StoredObject> list(String prefix) {
        List<StoredObject> result = new ArrayList<>();
        entries.forEach((key, entry) -> {
            if (key.startsWith(prefix)) {
                result.add(new StoredObject(key, entry.lastModified()));
            }
        });
        return result;
    }

    public byte[] bytes(String key) {
        return entries.get(key).bytes();
    }

    public int size() {
        return entries.size();
    }
}
