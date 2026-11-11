package com.example.videostream.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

/**
 * S3 client for the object store, and creation of the bucket at startup.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * Path-style access and "when required" checksums: SDK 2.30+ otherwise sends CRC checksums in
     * {@code aws-chunked} bodies, which several S3-compatible stores reject.
     */
    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean
    public ApplicationRunner bucketInitializer(S3Client s3, StorageProperties properties) {
        return args -> {
            if (!properties.createBucket()) {
                return;
            }
            String bucket = properties.bucket();
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created bucket {}", bucket);
                } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException race) {
                    log.debug("Bucket {} was created concurrently", bucket);
                }
            }
        };
    }
}
