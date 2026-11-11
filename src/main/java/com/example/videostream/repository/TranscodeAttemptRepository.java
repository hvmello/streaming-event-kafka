package com.example.videostream.repository;

import com.example.videostream.model.entity.TranscodeAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** The log of transcode attempts, which is where K3 (re-executions per video) is read from. */
@Repository
public interface TranscodeAttemptRepository extends JpaRepository<TranscodeAttempt, Long> {

    List<TranscodeAttempt> findByVideoIdOrderByStartedAt(Long videoId);

    boolean existsByVideoId(Long videoId);

    @Query("SELECT COUNT(DISTINCT a.videoId) FROM TranscodeAttempt a")
    long countTranscodedVideos();

    /**
     * Closes an attempt's row.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Query("UPDATE TranscodeAttempt a SET a.endedAt = :endedAt, a.outcome = :outcome WHERE a.attemptId = :attemptId")
    int close(String attemptId, Instant endedAt, String outcome);

    /** K3: the transcodes beyond the first, over every video (attempts minus distinct videos). */
    default long countReexecutions() {
        return count() - countTranscodedVideos();
    }
}
