package com.example.videostream.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * In-memory {@link ObjectStore} for unit tests.
 */
public class InMemoryObjectStore implements ObjectStore {

    private record Entry(byte[] bytes, String contentType, Instant lastModified) {
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    /** Set to make {@link #delete} fail, to test best-effort cleanup. */
    public boolean failOnDelete;

    /** Keys in the order they were put, to assert write ordering. */
    public final List<String> putOrder = new ArrayList<>();

    /** Number of objects per page handed to {@link #forEachPage}. */
    public int pageSize = 1000;

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
            putOrder.add(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void get(String key, Path target) {
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalStateException("no such key " + key);
        }
        try {
            Files.write(target, entry.bytes());
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
    public void forEachPage(String prefix, Consumer<List<StoredObject>> pageConsumer) {
        List<StoredObject> page = new ArrayList<>();
        for (Map.Entry<String, Entry> e : new ArrayList<>(entries.entrySet())) {
            if (e.getKey().startsWith(prefix)) {
                page.add(new StoredObject(e.getKey(), e.getValue().lastModified()));
                if (page.size() == pageSize) {
                    pageConsumer.accept(page);
                    page = new ArrayList<>();
                }
            }
        }
        if (!page.isEmpty()) {
            pageConsumer.accept(page);
        }
    }

    public byte[] bytes(String key) {
        return entries.get(key).bytes();
    }

    public int size() {
        return entries.size();
    }
}
