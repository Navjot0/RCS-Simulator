package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response shape for POST /v1/media. "fileUrl" is a locally-served
 * shareable link (GET /media/{mediaId}), not a CDN URL, since this service
 * stores nothing durably.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadFileResponse {
    private String status;
    private String mediaId;
    private String fileUrl;
    private String fileName;
    private String contentType;
    private long fileSizeBytes;
    private Instant uploadedAt;
    private Instant expiresAt;
}
