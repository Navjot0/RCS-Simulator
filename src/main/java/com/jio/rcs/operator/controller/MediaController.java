package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.exception.ResourceNotFoundException;
import com.jio.rcs.operator.model.MediaBlob;
import com.jio.rcs.operator.registry.MediaStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Serves back the bytes stored by POST /v1/media. This whole simulator is
 * unauthenticated (see README) - a real shareable media link embedded in an
 * RCS rich card is fetched directly by the recipient's client anyway, so
 * this mirrors that access pattern regardless. Bytes exist only in the
 * in-memory {@link MediaStore}; once evicted (TTL) or after a restart, the
 * link 404s - this is a testing convenience, not a durable CDN.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Media", description = "Publicly fetch a previously uploaded file by its shareable link")
public class MediaController {

    private final MediaStore mediaStore;

    @GetMapping("/media/{mediaId}")
    @Operation(summary = "Fetch a previously uploaded media file by id (public, unauthenticated - mirrors a real shareable link)")
    public ResponseEntity<byte[]> getMedia(@PathVariable String mediaId) {
        MediaBlob blob = mediaStore.find(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("No media found for id " + mediaId + " (expired or never existed)"));

        if (blob.getExpiresAt() != null && blob.getExpiresAt().isBefore(Instant.now())) {
            mediaStore.remove(mediaId);
            throw new ResourceNotFoundException("Media " + mediaId + " has expired");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(blob.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + blob.getFilename() + "\"")
                .body(blob.getData());
    }
}
