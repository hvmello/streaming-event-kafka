package com.example.videostream.model.dto;

/**
 * Response of an accepted upload.
 *
 * @param objectKey key of the raw object in the object store
 */
public record UploadAcceptedDTO(String objectKey) {
}
