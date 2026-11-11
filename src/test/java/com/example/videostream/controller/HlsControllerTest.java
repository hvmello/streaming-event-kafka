package com.example.videostream.controller;

import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.InMemoryObjectStore;
import com.example.videostream.service.streaming.HlsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HlsControllerTest {

    private static final String PREFIX = "hls/content-1/attempt-b/";

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final VideoRepository videos = Mockito.mock(VideoRepository.class);
    private final Video video = new Video();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        video.setId(7L);
        video.setStatus(Video.VideoStatus.READY);
        video.setHlsManifestKey(PREFIX + "master.m3u8");
        Mockito.when(videos.findById(7L)).thenReturn(Optional.of(video));
        put(PREFIX + "master.m3u8", "#EXTM3U master");
        put(PREFIX + "720p/index.m3u8", "#EXTM3U variant");
        put(PREFIX + "720p/seg_000.ts", "segment-bytes");
        mvc = MockMvcBuilders.standaloneSetup(new HlsController(new HlsService(videos, store))).build();
    }

    private void put(String key, String body) {
        byte[] bytes = body.getBytes();
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    @Test
    void masterIsServedWithNoCache() throws Exception {
        mvc.perform(get("/api/videos/7/hls/master.m3u8"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string("#EXTM3U master"));
    }

    @Test
    void variantPlaylistAndSegmentAreServedImmutable() throws Exception {
        mvc.perform(get("/api/videos/7/hls/720p/index.m3u8"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", not(containsString("no-cache"))))
                .andExpect(content().string("#EXTM3U variant"));

        mvc.perform(get("/api/videos/7/hls/720p/seg_000.ts"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp2t"))
                .andExpect(header().string("Content-Length", "13"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(content().string("segment-bytes"));
    }

    @Test
    void unknownVideoIs404() throws Exception {
        mvc.perform(get("/api/videos/8/hls/master.m3u8")).andExpect(status().isNotFound());
    }

    @Test
    void videoThatIsNotReadyIs404() throws Exception {
        for (Video.VideoStatus status : Video.VideoStatus.values()) {
            if (status == Video.VideoStatus.READY) {
                continue;
            }
            video.setStatus(status);
            mvc.perform(get("/api/videos/7/hls/master.m3u8")).andExpect(status().isNotFound());
        }
    }

    @Test
    void missingObjectIs404() throws Exception {
        mvc.perform(get("/api/videos/7/hls/720p/seg_404.ts")).andExpect(status().isNotFound());
    }

    @Test
    void traversalAndOddPathsAre404WithoutTouchingTheStore() throws Exception {
        // Empty segments never reach the service: Spring drops them while parsing the path (the firewall
        // rejects them on a real server), and HlsServiceTest covers them directly.
        String[] paths = {
                "../master.m3u8",
                "720p/../../other/master.m3u8",
                "%2e%2e/master.m3u8",
                "720p%5Cseg_000.ts",
                "master.mp4",
                "720p/seg_000.txt"
        };
        for (String path : paths) {
            org.assertj.core.api.Assertions.assertThat(mvc.perform(get("/api/videos/7/hls/" + path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(404);
        }
        assertThat(store.openCalls).isZero();
    }
}
