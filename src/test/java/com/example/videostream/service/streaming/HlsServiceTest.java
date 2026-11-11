package com.example.videostream.service.streaming;

import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import com.example.videostream.service.storage.InMemoryObjectStore;
import com.example.videostream.service.streaming.HlsService.HlsObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HlsServiceTest {

    private static final long ID = 7L;
    private static final String PREFIX = "hls/content-1/attempt-b/";

    private final VideoRepository videos = Mockito.mock(VideoRepository.class);
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final HlsService service = new HlsService(videos, store);
    private Video video;

    @BeforeEach
    void setUp() {
        video = new Video();
        video.setId(ID);
        video.setContentId("content-1");
        video.setStatus(Video.VideoStatus.READY);
        video.setHlsManifestKey(PREFIX + "master.m3u8");
        Mockito.when(videos.findById(ID)).thenReturn(Optional.of(video));
        put(PREFIX + "master.m3u8", "master");
        put(PREFIX + "720p/index.m3u8", "variant");
        put(PREFIX + "720p/seg_000.ts", "segment");
        // Another attempt of the same content, and another video's content.
        put("hls/content-1/attempt-a/master.m3u8", "old master");
        put("hls/content-1/attempt-a/720p/seg_000.ts", "old segment");
        put("hls/other/attempt-x/master.m3u8", "other master");
        put("raw/content-1", "raw");
    }

    private void put(String key, String body) {
        byte[] bytes = body.getBytes();
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    private static String read(HlsObject object) throws IOException {
        try (var stream = object.content().stream()) {
            return new String(stream.readAllBytes());
        }
    }

    @Test
    void servesMasterVariantAndSegmentOfTheCurrentAttempt() throws IOException {
        HlsObject master = service.open(ID, "master.m3u8").orElseThrow();
        assertThat(master.contentType()).isEqualTo("application/vnd.apple.mpegurl");
        assertThat(master.master()).isTrue();
        assertThat(read(master)).isEqualTo("master");

        HlsObject variant = service.open(ID, "/720p/index.m3u8").orElseThrow();
        assertThat(variant.contentType()).isEqualTo("application/vnd.apple.mpegurl");
        assertThat(variant.master()).isFalse();
        assertThat(read(variant)).isEqualTo("variant");

        HlsObject segment = service.open(ID, "720p/seg_000.ts").orElseThrow();
        assertThat(segment.contentType()).isEqualTo("video/mp2t");
        assertThat(segment.content().contentLength()).isEqualTo(7);
        assertThat(read(segment)).isEqualTo("segment");
    }

    @Test
    void aNestedMasterIsNotTheMaster() {
        put(PREFIX + "720p/master.m3u8", "nested");
        assertThat(service.open(ID, "720p/master.m3u8").orElseThrow().master()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Video.VideoStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void aVideoThatIsNotReadyIsNotServed(Video.VideoStatus status) {
        video.setStatus(status);
        assertThat(service.open(ID, "master.m3u8")).isEmpty();
        assertThat(store.openCalls).isZero();
    }

    @Test
    void anUnknownVideoIsNotServed() {
        assertThat(service.open(99L, "master.m3u8")).isEmpty();
        assertThat(store.openCalls).isZero();
    }

    @Test
    void aReadyVideoWithoutAManifestKeyIsNotServed() {
        video.setHlsManifestKey(null);
        assertThat(service.open(ID, "master.m3u8")).isEmpty();
    }

    @Test
    void aMissingObjectIsNotServed() {
        assertThat(service.open(ID, "720p/seg_999.ts")).isEmpty();
        assertThat(store.openCalls).isEqualTo(1);
    }

    @Test
    void anObjectOfAnotherAttemptIsNotReachable() {
        // attempt-a holds 480p, but the row points at attempt-b, which does not.
        put("hls/content-1/attempt-a/480p/seg_000.ts", "old 480p");
        assertThat(service.open(ID, "480p/seg_000.ts")).isEmpty();
        assertThat(service.open(ID, "../attempt-a/480p/seg_000.ts")).isEmpty();
        assertThat(service.open(ID, "../../other/attempt-x/master.m3u8")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../master.m3u8",
            "720p/../../other/master.m3u8",
            "/../master.m3u8",
            "%2e%2e/master.m3u8",
            "%2E%2E/master.m3u8",
            "720p/%2e%2e/master.m3u8",
            "720p%2Fseg_000.ts",
            "720p\\seg_000.ts",
            "720p//seg_000.ts",
            "//master.m3u8",
            "720p/",
            "720p",
            "",
            "/",
            "master.mp4",
            "master.m3u8.bak",
            "master",
            ".m3u8",
            "seg.000.ts",
            "720p/seg_000.ts\u0000.m3u8",
            "720p/seg 000.ts",
            "720p/seg_000.TS",
            "720p/seg_000.ts\n"
    })
    void aPathOutsideTheAllowlistIsRejectedBeforeTheStoreIsTouched(String path) {
        assertThat(service.open(ID, path)).isEmpty();
        assertThat(store.openCalls).isZero();
        Mockito.verifyNoInteractions(videos);
    }

    @Test
    void aNullPathIsRejected() {
        assertThat(service.open(ID, null)).isEmpty();
        assertThat(store.openCalls).isZero();
    }
}
