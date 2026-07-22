package com.jio.rcs.operator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * In-memory, transient representation of a single uploaded media file
 * backing the /v1/media "shareable link" flow. Mirrors
 * MessageContext's philosophy: this is the ONLY place the file's bytes
 * live - nothing is written to disk. {@link com.jio.rcs.operator.scheduler.MediaStoreCleanupScheduler}
 * evicts entries once their retention window elapses, and everything is
 * lost on restart, same as the rest of this stateless simulator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaBlob {

    private String mediaId;
    private String filename;
    private String contentType;
    private long sizeBytes;
    private byte[] data;
    private Instant uploadedAt;
    private Instant expiresAt;
}
