package com.jio.rcs.operator.registry;

import com.jio.rcs.operator.model.MediaBlob;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, in-process, in-memory map of uploaded media blobs - the upload
 * equivalent of {@link MessageStore}. NOT a database and NOT disk storage:
 * bytes live only for the life of the process (or until
 * MediaStoreCleanupScheduler evicts them after their TTL), so the
 * "shareable link" returned by /v1/media is only usable for local
 * / short-lived RCS rich-card testing, never for real handset delivery.
 */
@Component
public class MediaStore {

    private final Map<String, MediaBlob> store = new ConcurrentHashMap<>();

    public void put(MediaBlob blob) {
        store.put(blob.getMediaId(), blob);
    }

    public Optional<MediaBlob> find(String mediaId) {
        return Optional.ofNullable(store.get(mediaId));
    }

    public Collection<MediaBlob> all() {
        return store.values();
    }

    public void remove(String mediaId) {
        store.remove(mediaId);
    }

    public int size() {
        return store.size();
    }
}
