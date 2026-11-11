package com.example.videostream.service.transcode;

/**
 * Keeps a transcode claim alive while its job runs. It is an interface so that a test can stop the heartbeat of
 * one attempt, the way a killed worker would.
 */
public interface LeaseRenewer {

    /** Starts renewing the claim of {@code attemptId} on {@code videoId}; closing the result stops it. */
    Lease start(Long videoId, String attemptId);

    /** A running heartbeat. */
    interface Lease extends AutoCloseable {

        /** Stops the heartbeat. */
        @Override
        void close();
    }
}
