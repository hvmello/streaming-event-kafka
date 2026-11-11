package com.example.videostream.controller;

import com.example.videostream.service.streaming.HlsService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves the HLS playlists and segments of a video from the object store. The playlists use relative URIs, so
 * the browser resolves them against this endpoint and nothing needs rewriting.
 */
@RestController
@RequestMapping("/api/videos/{videoId}/hls")
public class HlsController {

    private final HlsService hls;

    public HlsController(HlsService hls) {
        this.hls = hls;
    }

    @GetMapping("/{*path}")
    public ResponseEntity<Resource> serve(@PathVariable Long videoId, @PathVariable String path) {
        return hls.open(videoId, path)
                .map(object -> {
                    // The master URL is stable per video; everything else lives under an attempt-scoped prefix
                    // that never changes.
                    CacheControl cache = object.master()
                            ? CacheControl.noCache()
                            : CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
                    // An InputStreamResource is streamed and closed by the converter, also when the client
                    // disconnects mid-segment, and never buffered in the heap.
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(object.contentType()))
                            .contentLength(object.content().contentLength())
                            .cacheControl(cache)
                            .<Resource>body(new InputStreamResource(object.content().stream()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
