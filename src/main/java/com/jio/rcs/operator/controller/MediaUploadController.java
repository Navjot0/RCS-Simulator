package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.dto.response.UploadFileResponse;
import com.jio.rcs.operator.exception.MediaTooLargeException;
import com.jio.rcs.operator.exception.UnsupportedMediaFileException;
import com.jio.rcs.operator.model.MediaBlob;
import com.jio.rcs.operator.registry.MediaStore;
import com.jio.rcs.operator.util.IdGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Accepts a single media file (image/video/file) and stores it in memory
 * only - this service never writes anything to disk. The returned
 * "shareable link" (see {@link MediaController}) points back at this same
 * process's own public GET /media/{id} endpoint rather than a real CDN,
 * which is sufficient for local rich-card/carousel testing without
 * depending on external hosting.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Media Upload", description = "Upload a single media file and receive a shareable link for use in rich cards")
public class MediaUploadController {

    private final MediaStore mediaStore;
    private final ProviderProperties providerProperties;

    @PostMapping(value = "/v1/media", consumes = "multipart/form-data")
    @Operation(summary = "Upload a single media file (image/video/file) for use in RCS rich cards/carousels")
    public ResponseEntity<UploadFileResponse> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }

        var mediaConfig = providerProperties.getMedia();
        String contentType = file.getContentType();
        if (contentType == null || !mediaConfig.getAllowedContentTypes().contains(contentType.toLowerCase())) {
            throw new UnsupportedMediaFileException(
                    "content type '" + contentType + "' is not supported; allowed: " + mediaConfig.getAllowedContentTypes());
        }
        if (file.getSize() > mediaConfig.getMaxFileSizeBytes()) {
            throw new MediaTooLargeException(
                    "file size " + file.getSize() + " bytes exceeds the " + mediaConfig.getMaxFileSizeBytes() + " byte limit");
        }

        String mediaId = IdGenerator.mediaId();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(mediaConfig.getRetentionMinutes() * 60);

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file bytes", e);
        }

        MediaBlob blob = MediaBlob.builder()
                .mediaId(mediaId)
                .filename(file.getOriginalFilename())
                .contentType(contentType)
                .sizeBytes(file.getSize())
                .data(data)
                .uploadedAt(now)
                .expiresAt(expiresAt)
                .build();
        mediaStore.put(blob);

        String fileUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/media/")
                .path(mediaId)
                .toUriString();

        log.info("Stored uploaded media {} ({} bytes, {})", mediaId, file.getSize(), contentType);

        UploadFileResponse response = UploadFileResponse.builder()
                .status("SUCCESS")
                .mediaId(mediaId)
                .fileUrl(fileUrl)
                .fileName(file.getOriginalFilename())
                .contentType(contentType)
                .fileSizeBytes(file.getSize())
                .uploadedAt(now)
                .expiresAt(expiresAt)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
