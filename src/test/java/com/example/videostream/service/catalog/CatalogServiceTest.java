package com.example.videostream.service.catalog;

import com.example.videostream.model.dto.VideoDTO;
import com.example.videostream.model.entity.Video;
import com.example.videostream.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @Test
    void createVideoSavesUploadingVideoWithContentIdAndSegmentCount() {
        CatalogService service = new CatalogService(videoRepository);
        when(videoRepository.save(any(Video.class))).thenAnswer(inv -> inv.getArgument(0));

        VideoDTO dto = service.createVideo("Title", "Description", 60);

        ArgumentCaptor<Video> saved = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(saved.capture());
        Video video = saved.getValue();
        assertThat(video.getStatus()).isEqualTo(Video.VideoStatus.UPLOADING);
        assertThat(video.getContentId()).isNotBlank();
        assertThat(video.getSegmentCount()).isEqualTo(10);
        assertThat(dto.getTitle()).isEqualTo("Title");
        assertThat(dto.getContentId()).isEqualTo(video.getContentId());
    }
}
