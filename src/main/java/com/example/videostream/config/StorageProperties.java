package com.example.videostream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Object store (S3 API) settings.
 *
 * @param endpoint        S3 endpoint URL; path-style access is always used
 * @param region          signing region; most S3-compatible stores accept any value
 * @param bucket          bucket that holds the raw uploads
 * @param accessKey       access key
 * @param secretKey       secret key
 * @param orphanGrace     how old an unreferenced object must be before the cleaner deletes it. An upload writes
 *                        the object before its database row commits, so this must stay much longer than the
 *                        longest request (the upload size limit and the request timeout).
 * @param cleanupInterval delay between two cleaner runs
 * @param createBucket    create the bucket at startup when it is missing
 */
@ConfigurationProperties("videostream.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:8333") String endpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("videostream") String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("PT1H") Duration orphanGrace,
        @DefaultValue("PT10M") Duration cleanupInterval,
        @DefaultValue("true") boolean createBucket) {
}
