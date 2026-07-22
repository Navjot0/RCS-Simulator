package com.jio.rcs.operator.scheduler;

import com.jio.rcs.operator.model.MediaBlob;
import com.jio.rcs.operator.registry.MediaStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically sweeps expired media blobs out of the in-memory MediaStore,
 * the upload equivalent of {@link MessageStoreCleanupScheduler}. Keeps
 * memory bounded and keeps the "stores nothing durable" guarantee honest -
 * uploaded files are only ever reachable for operator.media.retention-minutes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaStoreCleanupScheduler {

    private final MediaStore mediaStore;

    @Scheduled(fixedDelayString = "${operator.media.cleanup-interval-millis:60000}")
    public void evictExpiredMedia() {
        Instant now = Instant.now();
        int evicted = 0;

        for (MediaBlob blob : mediaStore.all()) {
            if (blob.getExpiresAt() != null && blob.getExpiresAt().isBefore(now)) {
                mediaStore.remove(blob.getMediaId());
                evicted++;
            }
        }

        if (evicted > 0) {
            log.debug("Evicted {} expired media blob(s) from in-memory store; {} remaining", evicted, mediaStore.size());
        }
    }
}
