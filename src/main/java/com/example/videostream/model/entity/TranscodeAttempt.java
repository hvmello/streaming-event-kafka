package com.example.videostream.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One claim of a video for transcoding. The row is inserted in the transaction that takes the claim and closed in
 * the transaction of the attempt's last update, so the table is the record of how many times each video was
 * transcoded: re-executions of a video are its attempts minus one.
 *
 * <p>{@code videoId} is a plain column, not a foreign key: the log must outlive the video row it describes.
 */
@Entity
@Table(name = "transcode_attempts", indexes = @Index(name = "idx_transcode_attempts_video", columnList = "videoId"))
@Getter
@Setter
@NoArgsConstructor
public class TranscodeAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long videoId;

    @Column(nullable = false, unique = true, length = 36)
    private String attemptId;

    @Column(nullable = false)
    private Instant startedAt;

    /** Null while the attempt runs, and for an attempt whose worker died before it could close the row. */
    private Instant endedAt;

    /** The {@code TranscodeJob.Outcome} name; null while the attempt runs. */
    @Column(length = 20)
    private String outcome;

    /** True when an earlier attempt for the same video existed: this one is a re-execution. */
    @Column(nullable = false)
    private boolean reclaimed;

    public TranscodeAttempt(Long videoId, String attemptId, Instant startedAt, boolean reclaimed) {
        this.videoId = videoId;
        this.attemptId = attemptId;
        this.startedAt = startedAt;
        this.reclaimed = reclaimed;
    }
}
