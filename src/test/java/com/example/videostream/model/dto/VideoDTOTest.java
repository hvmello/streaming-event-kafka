package com.example.videostream.model.dto;

import com.example.videostream.model.entity.Video;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoDTOTest {

    @Test
    void playbackUrlIsSetOnlyWhenReady() {
        Video video = new Video();
        video.setId(5L);
        for (Video.VideoStatus status : Video.VideoStatus.values()) {
            video.setStatus(status);
            String expected = status == Video.VideoStatus.READY ? "/api/videos/5/hls/master.m3u8" : null;
            assertThat(VideoDTO.fromEntity(video).getPlaybackUrl()).as(status.name()).isEqualTo(expected);
        }
    }
}
