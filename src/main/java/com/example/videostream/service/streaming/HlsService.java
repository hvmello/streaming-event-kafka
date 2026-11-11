package com.example.videostream.service.streaming;

import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.ObjectStore;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a playback request to an object of the attempt that the database points at. Only a {@code READY}
 * video is served, and only below the prefix of its {@code hlsManifestKey}, so superseded or partial attempts
 * are unreachable.
 */
@Service
public class HlsService {

    static final String MASTER = "master.m3u8";

    /**
     * Segments of {@code [A-Za-z0-9_-]}, then a file name of the same characters with a {@code .m3u8} or
     * {@code .ts} extension. The single dot rules out {@code ..}; slashes only appear between non-empty parts.
     * Matched against the raw path, so an encoded dot ({@code %2e}) or a backslash never matches either.
     */
    private static final Pattern ALLOWED = Pattern.compile("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:m3u8|ts)");

    /**
     * What to send back: the open object plus the headers that depend on what it is.
     */
    public record HlsObject(ObjectStore.StoredContent content, String contentType, boolean master) {
    }

    private final VideoRepository videos;
    private final ObjectStore store;

    public HlsService(VideoRepository videos, ObjectStore store) {
        this.videos = videos;
        this.store = store;
    }

    /**
     * @param path path below the attempt prefix, with or without a leading slash, for example
     *             {@code 720p/seg_000.ts}
     * @return the open object, or empty for an unknown or not {@code READY} video, an invalid path or a missing
     *         object. The caller must close the stream.
     */
    public Optional<HlsObject> open(Long videoId, String path) {
        String relative = path != null && path.startsWith("/") ? path.substring(1) : path;
        if (relative == null || !ALLOWED.matcher(relative).matches()) {
            return Optional.empty();
        }
        return videos.findById(videoId)
                .filter(v -> v.getStatus() == Video.VideoStatus.READY)
                .map(Video::getHlsManifestKey)
                .filter(key -> key.endsWith("/" + MASTER))
                .map(key -> key.substring(0, key.length() - MASTER.length()))
                .flatMap(prefix -> store.open(prefix + relative))
                .map(content -> new HlsObject(content,
                        relative.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t",
                        relative.equals(MASTER)));
    }
}
