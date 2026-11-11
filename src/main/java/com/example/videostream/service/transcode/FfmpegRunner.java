package com.example.videostream.service.transcode;

import com.example.videostream.config.TranscodeProperties.Rendition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs one FFmpeg transcode. Production uses {@link ProcessFfmpegRunner}; unit tests use a stub that writes
 * fake files.
 */
public interface FfmpegRunner {

    /** Name of the master playlist the runner must write at the top of the output directory. */
    String MASTER_PLAYLIST = "master.m3u8";

    /**
     * Writes an HLS master playlist, one variant playlist and its segments per rendition under
     * {@code outputDir}. The caller uploads whatever ends up there, so file names are not part of the contract
     * except that the master playlist is {@link #MASTER_PLAYLIST}.
     *
     * @throws IOException          when FFmpeg cannot start, exits non-zero or times out
     * @throws InterruptedException when the calling thread is interrupted; the process is destroyed first
     */
    void transcode(Path input, Path outputDir, List<Rendition> renditions) throws IOException, InterruptedException;
}
